"""
传感器管理器
===========
管理所有模拟传感器的生命周期：添加、删除、启动、停止。
每个传感器在独立线程中按配置频率发布数据和心跳。

内部键格式: {deviceId}_{sensorId} (如 SL-001_1)
支持从后端 REST API 全量同步传感器列表。
"""

import logging
import threading
import time
from collections import deque
from datetime import datetime
from typing import Any, Callable, Dict, List, Optional

from sender.config_manager import ConfigManager, _make_sensor_key
from sender.data_generator import (
    generate_control_response,
    generate_heartbeat,
    generate_sensor_data,
)
from sender.mqtt_client import MqttClientManager

logger = logging.getLogger("mock-sender.sensor")


def _sensor_label(cfg: Dict[str, Any]) -> str:
    """生成传感器可读标签: 设备名/传感器名"""
    device_name = cfg.get("deviceName") or cfg.get("deviceId", "?")
    sensor_name = cfg.get("displayName") or cfg.get("sensorType") or cfg.get("deviceId", "?")
    return f"{device_name}/{sensor_name}"


# ---------------------------------------------------------------------------
# HTTP 请求辅助（内联，不引入额外依赖）
# ---------------------------------------------------------------------------

def _http_get_json(url: str, timeout: float = 10) -> Optional[Any]:
    """简单的 HTTP GET 返回 JSON，失败返回 None。"""
    import urllib.request
    import urllib.error
    try:
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            import json
            return json.loads(resp.read().decode())
    except Exception as e:
        logger.warning(f"HTTP GET {url} 失败: {e}")
        return None


class SensorWorker:
    """
    单个传感器的工作线程。
    定时发布传感器数据和心跳到 MQTT。
    """

    def __init__(
        self,
        sensor_key: str,
        config: Dict[str, Any],
        mqtt_client: MqttClientManager,
        sim_config: Optional[Dict[str, Any]] = None,
        on_stopped: Optional[Callable] = None,
        on_publish: Optional[Callable] = None,
    ):
        self.sensor_key = sensor_key
        self.device_id = config.get("deviceId", "")
        self.config = dict(config)
        self._mqtt = mqtt_client
        self._sim_config = sim_config or {}
        self._on_stopped = on_stopped
        self._on_publish = on_publish

        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._start_time = time.time()

        self._light_status = config.get("lightStatus", "off")
        self._control_mode = config.get("controlMode", "auto")

        self.publish_count = 0
        self.heartbeat_count = 0
        self.last_publish_time: Optional[float] = None

        self._label = _sensor_label(config)

    # ------------------------------------------------------------------
    # 属性
    # ------------------------------------------------------------------

    @property
    def is_running(self) -> bool:
        return self._running

    @property
    def light_status(self) -> str:
        return self._light_status

    @property
    def control_mode(self) -> str:
        return self._control_mode

    @property
    def uptime(self) -> float:
        return time.time() - self._start_time if self._running else 0

    @property
    def interval(self) -> int:
        return self.config.get("interval", 5)

    @property
    def display_name(self) -> str:
        return self.config.get("displayName", "")

    @property
    def sensor_type(self) -> str:
        return self.config.get("sensorType", "light")

    @property
    def device_name(self) -> str:
        return self.config.get("deviceName", "")

    # ------------------------------------------------------------------
    # 控制
    # ------------------------------------------------------------------

    def start(self) -> bool:
        if self._running:
            return False
        self._running = True
        self._start_time = time.time()
        self._thread = threading.Thread(
            target=self._run_loop,
            name=f"sensor-{self.sensor_key}",
            daemon=True,
        )
        self._thread.start()
        logger.info(f"传感器 {self._label} 已启动 (interval={self.interval}s)")
        return True

    def stop(self) -> None:
        self._running = False
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=3)
        logger.info(f"传感器 {self._label} 已停止")
        if self._on_stopped:
            self._on_stopped(self.sensor_key)

    def update_config(self, updates: Dict[str, Any]) -> None:
        old_mode = self._control_mode
        self.config.update(updates)
        self._label = _sensor_label(self.config)
        # 同步运行时字段：_control_mode 和 _light_status 需与 config 保持一致
        if "controlMode" in updates:
            self._control_mode = updates["controlMode"]
        if "lightStatus" in updates:
            self._light_status = updates["lightStatus"]
        # 模式切换日志
        new_mode = self._control_mode
        if "controlMode" in updates and old_mode != new_mode:
            logger.info(f"传感器 [{self._label}] 模式切换: {old_mode} → {new_mode}")

    def set_light_status(self, status: str) -> None:
        self._light_status = status

    def set_control_mode(self, mode: str) -> None:
        self._control_mode = mode

    def publish_once(self) -> bool:
        """手动触发一次数据发送（用于手动模式下的单次发送）。"""
        return self._do_publish()

    # ------------------------------------------------------------------
    # 内部循环
    # ------------------------------------------------------------------

    def _do_publish(self) -> bool:
        """执行一次传感器数据发布（v2 真实时钟驱动 + 自定义消息模板），返回是否成功。

        发送模式:
          - algorithm (默认): 真实时钟太阳模型动态生成数据
          - fixed: 发送用户编辑的固定内容 (autoSendContent)
        """
        now = time.time()
        import json as _json

        data_range = self.config.get("dataRange", {"min": 0, "max": 800})
        data_topic = self.config.get("dataTopic", "")
        brightness = self.config.get("brightness")
        my_sensor_type = self.sensor_type
        my_sensor_id = self.config.get("sensorId")
        actual_topic = data_topic or f"streetlight/{self.device_id}/sensor/data"
        extra = {
            "location": self.config.get("location", ""),
            "controlMode": self._control_mode,
            "sensorType": self.sensor_type,
            "displayName": self.display_name,
        }

        # ---- 发送模式判断 ----
        send_mode = self.config.get("autoSendMode", "algorithm")

        if send_mode == "fixed":
            # ★ 固定内容模式: 发送用户编辑的静态消息
            fixed_content = self.config.get("autoSendContent", "")
            if fixed_content and fixed_content.strip():
                # 替换时间戳变量
                from datetime import datetime, timezone
                ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
                content = fixed_content.replace("{{timestamp}}", ts)
                content = content.replace("{{deviceId}}", self.device_id)
                try:
                    payload = _json.loads(content)
                except (_json.JSONDecodeError, TypeError):
                    logger.warning(f"[{self._label}] 固定内容格式错误，回退到算法模式")
                    payload = generate_sensor_data(
                        device_id=self.device_id,
                        light_status=self._light_status,
                        data_range=data_range,
                        extra_fields=extra,
                        brightness=brightness,
                        sim_config=self._sim_config,
                        sensor_type=my_sensor_type,
                        sensor_id=my_sensor_id,
                    )
            else:
                # 固定内容为空，使用算法生成
                payload = generate_sensor_data(
                    device_id=self.device_id,
                    light_status=self._light_status,
                    data_range=data_range,
                    extra_fields=extra,
                    brightness=brightness,
                    sim_config=self._sim_config,
                    sensor_type=my_sensor_type,
                    sensor_id=my_sensor_id,
                )
        else:
            # ---- 算法模式: 使用 messageTemplate 或默认生成 ----
            message_template = self.config.get("messageTemplate", "")
            if message_template and message_template.strip():
                from sender.data_generator import generate_sensor_data_with_template
                msg_str = generate_sensor_data_with_template(
                    template=message_template,
                    device_id=self.device_id,
                    light_status=self._light_status,
                    data_range=data_range,
                    extra_fields=extra,
                    brightness=brightness,
                    sim_config=self._sim_config,
                    sensor_type=my_sensor_type,
                    sensor_id=my_sensor_id,
                )
                try:
                    payload = _json.loads(msg_str)
                except (_json.JSONDecodeError, TypeError):
                    payload = generate_sensor_data(
                        device_id=self.device_id,
                        light_status=self._light_status,
                        data_range=data_range,
                        extra_fields=extra,
                        brightness=brightness,
                        sim_config=self._sim_config,
                        sensor_type=my_sensor_type,
                        sensor_id=my_sensor_id,
                    )
            else:
                payload = generate_sensor_data(
                    device_id=self.device_id,
                    light_status=self._light_status,
                    data_range=data_range,
                    extra_fields=extra,
                    brightness=brightness,
                    sim_config=self._sim_config,
                    sensor_type=my_sensor_type,
                    sensor_id=my_sensor_id,
                )

        ok = self._mqtt.publish_sensor_data(self.device_id, payload, data_topic)
        if ok:
            self.publish_count += 1
            self.last_publish_time = now
            if self._on_publish:
                self._on_publish(
                    self.sensor_key, self.device_id,
                    self.device_name, self.display_name,
                    actual_topic, payload,
                )
        return ok

    def _run_loop(self) -> None:
        last_heartbeat_time = 0.0
        heartbeat_interval = 10

        while self._running:
            now = time.time()

            # 手动模式：只响应手动触发，不自动发送
            if self._control_mode == 'manual':
                time.sleep(0.5)
                continue

            # 自动发送传感器数据
            self._do_publish()

            # 定时发布心跳
            if now - last_heartbeat_time >= heartbeat_interval:
                hb_payload = generate_heartbeat(self.device_id)
                hb_payload["sensorKey"] = self.sensor_key
                if self._mqtt.publish_heartbeat(self.device_id, hb_payload):
                    self.heartbeat_count += 1
                last_heartbeat_time = now

            time.sleep(self.interval)


class SensorManager:
    """传感器管理器，管理所有 SensorWorker。"""

    def __init__(self, config_mgr: ConfigManager, mqtt_mgr: MqttClientManager):
        self._config_mgr = config_mgr
        self._mqtt_mgr = mqtt_mgr
        self._workers: Dict[str, SensorWorker] = {}
        self._lock = threading.Lock()

        # 发送历史记录（最近 200 条）
        self._history: deque = deque(maxlen=200)
        self._history_lock = threading.Lock()

        # 注册确认事件
        self._registration_ack_event = threading.Event()
        self._registration_ack_result: Optional[Dict[str, Any]] = None

    # ------------------------------------------------------------------
    # 传感器列表
    # ------------------------------------------------------------------

    def list_sensors(self) -> List[Dict[str, Any]]:
        """返回所有传感器的摘要信息。"""
        result = []
        with self._lock:
            for sensor_key, worker in self._workers.items():
                cfg = worker.config
                result.append({
                    "sensorKey": sensor_key,
                    "deviceId": worker.device_id,
                    "sensorId": cfg.get("sensorId", ""),
                    "displayName": worker.display_name,
                    "sensorType": worker.sensor_type,
                    "deviceName": worker.device_name,
                    "name": cfg.get("name", ""),
                    "location": cfg.get("location", ""),
                    "dataTopic": cfg.get("dataTopic", ""),
                    "running": worker.is_running,
                    "interval": worker.interval,
                    "lightStatus": worker.light_status,
                    "controlMode": worker.control_mode,
                    "publishCount": worker.publish_count,
                    "heartbeatCount": worker.heartbeat_count,
                    "uptime": round(worker.uptime, 1),
                    "lastPublish": worker.last_publish_time,
                    "autoSendMode": worker.config.get("autoSendMode", "algorithm"),
                    "autoSendContent": worker.config.get("autoSendContent", ""),
                })
        return result

    def get_sensor(self, sensor_key: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            worker = self._workers.get(sensor_key)
            if not worker:
                return None
            cfg = worker.config
            return {
                "sensorKey": sensor_key,
                "deviceId": worker.device_id,
                "sensorId": cfg.get("sensorId", ""),
                "displayName": worker.display_name,
                "sensorType": worker.sensor_type,
                "deviceName": worker.device_name,
                "name": cfg.get("name", ""),
                "location": cfg.get("location", ""),
                "dataTopic": cfg.get("dataTopic", ""),
                "running": worker.is_running,
                "interval": worker.interval,
                "lightStatus": worker.light_status,
                "controlMode": worker.control_mode,
                "publishCount": worker.publish_count,
                "heartbeatCount": worker.heartbeat_count,
                "uptime": round(worker.uptime, 1),
                "lastPublish": worker.last_publish_time,
                "dataRange": worker.config.get("dataRange", {"min": 0, "max": 800}),
                "messageTemplate": worker.config.get("messageTemplate", ""),
                "autoSendMode": worker.config.get("autoSendMode", "algorithm"),
                "autoSendContent": worker.config.get("autoSendContent", ""),
            }

    # ------------------------------------------------------------------
    # 添加 / 删除
    # ------------------------------------------------------------------

    def add_sensor(self, device_id: str, sensor_cfg: Dict[str, Any]) -> Optional[str]:
        """添加传感器并自动启动，返回传感器键。同时通过 MQTT 发布传感器注册。"""
        with self._lock:
            sensor_id = sensor_cfg.get("sensorId", sensor_cfg.get("id"))
            if sensor_id is None:
                sensor_id = int(time.time() * 1000) % 100000
            key = _make_sensor_key(device_id, sensor_id)

            if key in self._workers:
                logger.warning(f"传感器 {key} 已存在，跳过")
                return None

            # 保存到配置
            saved_key = self._config_mgr.add_sensor(device_id, sensor_cfg)
            if not saved_key:
                return None

            cfg = self._config_mgr.get_sensor(saved_key)
            if not cfg:
                return None

            worker = SensorWorker(saved_key, cfg, self._mqtt_mgr,
                                  sim_config=self._config_mgr.get_simulation_config(),
                                  on_publish=self._record_publish)
            self._workers[saved_key] = worker

        # 订阅控制主题
        self._mqtt_mgr.subscribe_device(device_id)

        worker.start()
        logger.info(f"已添加传感器: {_sensor_label(cfg)} (key={saved_key})")

        # 通过 MQTT 发布传感器动态注册
        sensor_info = {
            "sensorType": cfg.get("sensorType", "light"),
            "displayName": cfg.get("displayName", ""),
            "dataTopic": cfg.get("dataTopic", ""),
            "reportFrequency": cfg.get("interval", 5),
            "configJson": cfg.get("configJson", ""),
            "enabled": True,
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
        self._mqtt_mgr.publish_sensor_register(device_id, sensor_info)

        return saved_key

    def remove_sensor(self, sensor_key: str) -> bool:
        """停止并移除传感器。同时通过 MQTT 发布传感器注销。"""
        with self._lock:
            worker = self._workers.pop(sensor_key, None)
            if not worker:
                return False

        cfg = worker.config
        sensor_id = cfg.get("sensorId")

        worker.stop()
        self._mqtt_mgr.unsubscribe_device(worker.device_id)
        self._config_mgr.remove_sensor(sensor_key)
        logger.info(f"已移除传感器: {_sensor_label(cfg)} (key={sensor_key})")

        # 通过 MQTT 发布传感器注销
        if sensor_id is not None:
            self._mqtt_mgr.publish_sensor_unregister(worker.device_id, int(sensor_id))

        return True

    # ------------------------------------------------------------------
    # 启停
    # ------------------------------------------------------------------

    def start_sensor(self, sensor_key: str) -> bool:
        with self._lock:
            worker = self._workers.get(sensor_key)
            if not worker:
                return False
            return worker.start()

    def stop_sensor(self, sensor_key: str) -> bool:
        with self._lock:
            worker = self._workers.get(sensor_key)
            if not worker:
                return False
            worker.stop()
            return True

    # ------------------------------------------------------------------
    # 配置更新
    # ------------------------------------------------------------------

    def update_sensor_config(self, sensor_key: str, updates: Dict[str, Any]) -> bool:
        with self._lock:
            worker = self._workers.get(sensor_key)
            if not worker:
                return False
            worker.update_config(updates)
            self._config_mgr.update_sensor(sensor_key, updates)
            logger.info(f"传感器 {_sensor_label(worker.config)} 配置已更新: {updates}")
            return True

    # ------------------------------------------------------------------
    # 控制指令处理
    # ------------------------------------------------------------------

    def handle_control_command(self, device_id: str, payload: Dict[str, Any]) -> None:
        """处理下发的开关灯控制指令（作用于设备的所有传感器）。

        关键设计：
          - 手动指令(source=manual) → worker 切换为手动模式，停止自动上报传感器数据
          - 自动指令(source=auto)   → worker 保持/恢复自动模式，继续周期性上报
        这样确保手动控制后不会被自动联动覆盖。
        """
        command = payload.get("command", "")
        source = payload.get("source", "manual")
        brightness = payload.get("brightness")  # 可选，0-100

        affected = []
        with self._lock:
            for sensor_key, worker in self._workers.items():
                if worker.device_id == device_id:
                    if command == "on":
                        worker.set_light_status("on")
                        if brightness is not None:
                            worker.config["brightness"] = brightness
                    elif command == "off":
                        worker.set_light_status("off")
                        worker.config.pop("brightness", None)
                    else:
                        logger.warning(f"未知控制指令: {command}")
                        return

                    # ★ 修复：根据指令来源同步控制模式
                    # 手动指令 → 切换为手动模式（停止自动上报，防止自动联动覆盖）
                    # 自动指令 → 保持自动模式
                    new_mode = "manual" if source == "manual" else "auto"
                    old_mode = worker.control_mode
                    if old_mode != new_mode:
                        worker.set_control_mode(new_mode)
                        worker.config["controlMode"] = new_mode
                        logger.info(f"传感器 [{_sensor_label(worker.config)}] 模式随指令切换: "
                                    f"{old_mode} → {new_mode} (指令来源: {source})")

                    affected.append(sensor_key)

        if not affected:
            logger.warning(f"控制指令: 未知设备 {device_id}")
            return

        brightness_info = f", 亮度={brightness}%" if brightness is not None else ""
        logger.info(f"设备 {device_id} 灯光 -> {command}{brightness_info} (来源: {source}, 影响 {len(affected)} 个传感器)")

        response = generate_control_response(device_id, command, "success")
        response["source"] = source
        if brightness is not None:
            response["brightness"] = brightness
        self._mqtt_mgr.publish_control_response(device_id, response)

    # ------------------------------------------------------------------
    # 批量加载
    # ------------------------------------------------------------------

    def load_from_config(self) -> int:
        """从本地配置文件中加载所有 enabled 的传感器。"""
        sensors = self._config_mgr.get_all_sensors()
        count = 0
        for sensor_key, cfg in sensors.items():
            if cfg.get("enabled", True) and sensor_key not in self._workers:
                worker = SensorWorker(sensor_key, cfg, self._mqtt_mgr,
                                      sim_config=self._config_mgr.get_simulation_config(),
                                      on_publish=self._record_publish)
                with self._lock:
                    self._workers[sensor_key] = worker
                self._mqtt_mgr.subscribe_device(cfg.get("deviceId", ""))
                worker.start()
                count += 1
                logger.info(f"[本地加载] {_sensor_label(cfg)} (key={sensor_key})")
        logger.info(f"从本地配置加载了 {count} 个传感器")
        return count

    # ------------------------------------------------------------------
    # MQTT Broker 注册 / 注销
    # ------------------------------------------------------------------

    def register_to_broker(self) -> bool:
        """
        通过 MQTT 向 Broker 注册当前设备及其传感器列表。
        发送注册消息后等待 ACK 确认，收到确认后启动所有传感器。

        返回 True 表示注册成功并已启动传感器。
        """
        device_cfg = self._config_mgr.get_device_config()
        device_id = device_cfg.get("deviceId", "")
        if not device_id:
            logger.error("设备 ID 未配置，无法注册到 Broker")
            return False

        # 构建注册 payload
        sim_config = self._config_mgr.get_simulation_config()
        sensors_list = []
        all_sensors = self._config_mgr.get_all_sensors()
        for key, cfg in all_sensors.items():
            if cfg.get("enabled", True):
                sensors_list.append({
                    "sensorType": cfg.get("sensorType", "light"),
                    "displayName": cfg.get("displayName", ""),
                    "dataTopic": cfg.get("dataTopic", ""),
                    "reportFrequency": cfg.get("interval", cfg.get("reportFrequency", 5)),
                    "enabled": True,
                    "configJson": cfg.get("configJson", ""),
                    "messageTemplate": cfg.get("messageTemplate", ""),
                    "autoSendMode": cfg.get("autoSendMode", "algorithm"),
                    "autoSendContent": cfg.get("autoSendContent", ""),
                })

        payload = {
            "deviceId": device_id,
            "name": device_cfg.get("name", device_id),
            "location": device_cfg.get("location", ""),
            "sensors": sensors_list,
            "simConfig": {
                "latitude": sim_config.get("latitude", 29.5),
                "longitude": sim_config.get("longitude", 106.5),
                "timezoneOffset": sim_config.get("timezoneOffset", 8),
            },
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }

        logger.info(f"正在向 Broker 注册设备: deviceId={device_id}, sensors={len(sensors_list)}")
        ok = self._mqtt_mgr.publish_registration(payload)
        if not ok:
            logger.error("设备注册消息发送失败")
            return False

        # 注册成功 → 启动所有配置中的传感器
        logger.info("设备注册消息已发送，启动传感器...")
        self.load_from_config()
        return True

    def unregister_from_broker(self) -> bool:
        """通过 MQTT 发送设备注销消息。"""
        device_cfg = self._config_mgr.get_device_config()
        device_id = device_cfg.get("deviceId", "")
        if not device_id:
            return False
        logger.info(f"正在注销设备: deviceId={device_id}")
        return self._mqtt_mgr.publish_deregistration(device_id)

    # ------------------------------------------------------------------
    # 发送历史
    # ------------------------------------------------------------------

    def _record_publish(self, sensor_key: str, device_id: str,
                        device_name: str, display_name: str,
                        topic: str, payload: Dict[str, Any]) -> None:
        """记录一次成功发送到历史队列。"""
        try:
            with self._history_lock:
                self._history.append({
                    "time": datetime.now().strftime("%H:%M:%S"),
                    "sensorKey": sensor_key,
                    "deviceId": device_id,
                    "deviceName": device_name,
                    "displayName": display_name,
                    "topic": topic,
                    "payload": payload,
                })
        except Exception:
            pass

    def get_history(self, filter_key: str = "") -> List[Dict[str, Any]]:
        """获取发送历史，可按 sensorKey 筛选。"""
        with self._history_lock:
            history = list(self._history)
        if filter_key:
            history = [h for h in history if h["sensorKey"] == filter_key]
        return list(reversed(history))  # 最新的在前

    def clear_history(self) -> None:
        """清空发送历史。"""
        with self._history_lock:
            self._history.clear()
        logger.info("发送历史已清空")

    # ------------------------------------------------------------------
    # 批量启停（不删除传感器）
    # ------------------------------------------------------------------

    def stop_all_sending(self) -> int:
        """停止所有传感器的自动发送（保留 worker，可恢复）。"""
        count = 0
        with self._lock:
            for worker in self._workers.values():
                if worker.is_running:
                    worker.stop()
                    count += 1
        logger.info(f"已停止 {count} 个传感器的发送")
        return count

    def start_all(self) -> int:
        """启动所有已停止的传感器。"""
        count = 0
        with self._lock:
            for worker in self._workers.values():
                if not worker.is_running:
                    if worker.start():
                        count += 1
        logger.info(f"已启动 {count} 个传感器")
        return count

    def publish_once(self, sensor_key: str) -> bool:
        """手动触发单次发送。"""
        with self._lock:
            worker = self._workers.get(sensor_key)
            if not worker:
                return False
        return worker.publish_once()

    # ------------------------------------------------------------------
    # 全局停止（删除传感器）
    # ------------------------------------------------------------------

    def stop_all(self) -> None:
        with self._lock:
            keys = list(self._workers.keys())
        for key in keys:
            self.remove_sensor(key)
        logger.info("已停止所有传感器")

    # ------------------------------------------------------------------
    # 解绑 / 换绑（直接操作数据库）
    # ------------------------------------------------------------------

    # ------------------------------------------------------------------
    # 解绑 / 换绑（纯本地操作 + MQTT 通知）
    # ------------------------------------------------------------------

    def unbind_sensor(self, sensor_key: str, sensor_id: int) -> bool:
        """
        解绑传感器: 停止数据发送，通过 MQTT 通知后端标记传感器未绑定。
        """
        with self._lock:
            worker = self._workers.get(sensor_key)
        if worker:
            worker.stop()
            self._mqtt_mgr.unsubscribe_device(worker.device_id)
            worker.config["deviceId"] = ""
            worker.config["deviceName"] = ""
            worker.device_id = ""

        # 通过 MQTT 通知后端
        self._mqtt_mgr.publish_sensor_unregister(worker.device_id if worker else "", sensor_id)

        self._config_mgr.remove_sensor(sensor_key)
        new_key = _make_sensor_key("", sensor_id)
        if worker:
            cfg = dict(worker.config)
            self._config_mgr.add_sensor("", cfg)
            with self._lock:
                self._workers.pop(sensor_key, None)
                self._workers[new_key] = worker
            worker.sensor_key = new_key
            worker._label = _sensor_label(cfg)

        logger.info(f"传感器 {sensor_key} 已解绑 -> {new_key}")
        return True

    def rebind_sensor(self, sensor_key: str, sensor_id: int,
                      new_device_id: str) -> Optional[str]:
        """
        换绑传感器到新设备: 停止旧 worker，创建新 worker，通过 MQTT 通知后端。
        """
        with self._lock:
            old_worker = self._workers.pop(sensor_key, None)
        if old_worker:
            old_worker.stop()
            self._mqtt_mgr.unsubscribe_device(old_worker.device_id)

        self._config_mgr.remove_sensor(sensor_key)

        new_key = _make_sensor_key(new_device_id, sensor_id)
        cfg = dict(old_worker.config) if old_worker else {
            "sensorId": sensor_id, "deviceId": new_device_id,
        }
        cfg["deviceId"] = new_device_id

        self._config_mgr.add_sensor(new_device_id, cfg)

        worker = SensorWorker(new_key, cfg, self._mqtt_mgr,
                              sim_config=self._config_mgr.get_simulation_config(),
                              on_publish=self._record_publish)
        with self._lock:
            self._workers[new_key] = worker

        self._mqtt_mgr.subscribe_device(new_device_id)
        worker.start()

        # 通过 MQTT 发布传感器注册
        sensor_info = {
            "sensorType": cfg.get("sensorType", "light"),
            "displayName": cfg.get("displayName", ""),
            "dataTopic": cfg.get("dataTopic", ""),
            "reportFrequency": cfg.get("interval", 5),
            "configJson": cfg.get("configJson", ""),
            "enabled": True,
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
        self._mqtt_mgr.publish_sensor_register(new_device_id, sensor_info)

        logger.info(f"传感器已换绑: {sensor_key} -> {new_key}")
        return new_key

    def sync_from_database(self) -> int:
        """[已废弃] 模拟器不再直连数据库，改用 register_to_broker()。"""
        logger.warning("sync_from_database() 已废弃，请使用 register_to_broker()")
        return 0
