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
                    )
            else:
                payload = generate_sensor_data(
                    device_id=self.device_id,
                    light_status=self._light_status,
                    data_range=data_range,
                    extra_fields=extra,
                    brightness=brightness,
                    sim_config=self._sim_config,
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

    def __init__(self, config_mgr: ConfigManager, mqtt_mgr: MqttClientManager,
                 db_client=None):
        self._config_mgr = config_mgr
        self._mqtt_mgr = mqtt_mgr
        self._db_client = db_client
        self._workers: Dict[str, SensorWorker] = {}
        self._lock = threading.Lock()

        # 发送历史记录（最近 200 条）
        self._history: deque = deque(maxlen=200)
        self._history_lock = threading.Lock()

    def set_db_client(self, db_client) -> None:
        """热更新数据库客户端（用于配置变更后重连）。"""
        self._db_client = db_client

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
        """添加传感器并自动启动，返回传感器键。"""
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
        return saved_key

    def remove_sensor(self, sensor_key: str) -> bool:
        """停止并移除传感器。"""
        with self._lock:
            worker = self._workers.pop(sensor_key, None)
            if not worker:
                return False

        worker.stop()
        self._mqtt_mgr.unsubscribe_device(worker.device_id)
        self._config_mgr.remove_sensor(sensor_key)
        logger.info(f"已移除传感器: {_sensor_label(worker.config)} (key={sensor_key})")
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

    def load_from_backend(self) -> int:
        """
        全量同步传感器列表 —— DB 优先，REST API 作为 fallback。
        """
        db_cfg = self._config_mgr.get_database_config()
        if db_cfg and db_cfg.get("host") and self._db_client:
            try:
                return self._load_from_database()
            except Exception as e:
                logger.warning(f"数据库连接失败，回退到 REST API: {e}")
        return self._load_from_rest_api()

    def _load_from_database(self) -> int:
        """
        从 MySQL sensor 表直接加载所有已启用的传感器。
        支持 device_id=NULL 的无主传感器（key 格式: unbound_{id}）。
        """
        if not self._db_client:
            logger.warning("数据库客户端未初始化，跳过数据库加载")
            return 0

        logger.info("开始从数据库同步传感器...")
        rows = self._db_client.get_all_sensors()
        if not rows:
            logger.info("数据库中暂无传感器记录")
            return 0

        logger.info(f"数据库查询到 {len(rows)} 条传感器记录")

        total_added = 0
        sim_config = self._config_mgr.get_simulation_config()

        for row in rows:
            sensor_id = row.get("id")
            device_id = row.get("device_id") or ""  # NULL → ""
            sensor_type = row.get("sensor_type", "light")
            display_name = row.get("display_name", "")
            data_topic = row.get("data_topic", "")
            report_frequency = row.get("report_frequency", 5)
            enabled = row.get("enabled", 1)
            config_json = row.get("config_json", "")

            # 跳过禁用的传感器
            if not enabled:
                logger.debug(f"跳过已禁用的传感器 DB id={sensor_id}")
                continue

            # 生成 sensor key
            key = _make_sensor_key(device_id, sensor_id)
            if not key:
                continue

            # 已存在的跳过
            if key in self._workers:
                continue

            # 解析 config_json 获取数据范围
            data_range = {"min": 0, "max": 800}
            if config_json:
                try:
                    import json
                    cfg = json.loads(config_json)
                    if isinstance(cfg, dict):
                        data_range = {
                            "min": cfg.get("min", 0),
                            "max": cfg.get("max", 800),
                        }
                except (json.JSONDecodeError, TypeError):
                    pass

            # 查询设备名（如果有 device_id）
            device_name = ""
            location = ""
            if device_id:
                devices = self._db_client.get_all_devices()
                for dev in devices:
                    if dev.get("device_id") == device_id:
                        device_name = dev.get("name", "")
                        location = dev.get("location", "")
                        break

            sensor_cfg = {
                "sensorId": sensor_id,
                "deviceId": device_id,
                "displayName": display_name,
                "sensorType": sensor_type,
                "deviceName": device_name,
                "dataTopic": data_topic,
                "interval": report_frequency,
                "enabled": bool(enabled),
                "location": location,
                "configJson": config_json,
                "dataRange": data_range,
            }

            # 使用内部方法直接创建 worker（绕过 add_sensor 的配置持久化）
            with self._lock:
                worker = SensorWorker(
                    key, sensor_cfg, self._mqtt_mgr,
                    sim_config=sim_config,
                    on_publish=self._record_publish,
                )
                self._workers[key] = worker

            # 订阅控制主题
            mqtt_device_id = device_id if device_id else f"unbound/{sensor_id}"
            self._mqtt_mgr.subscribe_device(device_id if device_id else mqtt_device_id)

            worker.start()
            total_added += 1
            label = _sensor_label(sensor_cfg)
            logger.info(f"[DB加载] {label} (key={key})")

        logger.info(f"从数据库同步了 {total_added} 个传感器")
        return total_added

    def _load_from_rest_api(self) -> int:
        """
        [Fallback] 从后端 REST API 全量同步传感器列表。
        步骤:
          1. GET /api/devices → 获取所有设备
          2. 对每个设备 GET /api/devices/{deviceId}/sensors → 获取设备下的传感器
          3. 将所有传感器添加到本地
        """
        backend_url = self._config_mgr.get_backend_url()
        logger.info(f"开始从后端同步传感器 (REST API fallback): {backend_url}")

        # 1. 获取所有设备
        devices = _http_get_json(f"{backend_url}/devices", timeout=10)
        if not devices:
            logger.warning("无法获取设备列表，后端可能未启动")
            return 0

        if isinstance(devices, dict):
            devices = devices.get("data", devices.get("content", []))

        if not isinstance(devices, list):
            logger.warning(f"设备列表格式异常: {type(devices)}")
            return 0

        logger.info(f"获取到 {len(devices)} 个设备")

        # 2. 遍历每个设备获取传感器
        total_added = 0
        for device in devices:
            device_id = device.get("deviceId", "")
            device_name = device.get("name", device_id)
            if not device_id:
                continue

            sensors_resp = _http_get_json(f"{backend_url}/devices/{device_id}/sensors", timeout=10)
            if not sensors_resp:
                continue

            if isinstance(sensors_resp, dict):
                sensor_list = sensors_resp.get("data", sensors_resp.get("content", []))
            elif isinstance(sensors_resp, list):
                sensor_list = sensors_resp
            else:
                continue

            if not sensor_list:
                logger.info(f"设备 {device_name} ({device_id}) 暂无传感器")
                continue

            for sensor in sensor_list:
                sensor_cfg = {
                    "sensorId": sensor.get("id"),
                    "displayName": sensor.get("displayName", ""),
                    "sensorType": sensor.get("sensorType", "light"),
                    "deviceName": device_name,
                    "deviceId": device_id,
                    "dataTopic": sensor.get("dataTopic", ""),
                    "interval": sensor.get("reportFrequency", 5),
                    "enabled": sensor.get("enabled", True),
                    "location": device.get("location", ""),
                    "configJson": sensor.get("configJson", ""),
                }

                key = self.add_sensor(device_id, sensor_cfg)
                if key:
                    total_added += 1

        logger.info(f"从后端同步了 {total_added} 个传感器")
        return total_added

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

    def unbind_sensor(self, sensor_key: str, sensor_id: int) -> bool:
        """
        解绑传感器: 在 DB 中将 device_id 设为 NULL，
        传感器保留在列表中显示为"未绑定"状态，停止数据发送。

        Args:
            sensor_key: 传感器内部键
            sensor_id: 数据库传感器主键 ID

        Returns:
            bool: 是否成功
        """
        if not self._db_client:
            logger.error("数据库客户端未初始化，无法执行解绑操作")
            return False

        # 1. 更新数据库: device_id = NULL
        db_ok = self._db_client.unbind_sensor(sensor_id)
        if not db_ok:
            logger.error(f"数据库解绑失败: sensor DB id={sensor_id}")
            return False

        # 2. 停止 worker 但保留在列表中（标记为未绑定）
        with self._lock:
            worker = self._workers.get(sensor_key)
        if worker:
            worker.stop()
            self._mqtt_mgr.unsubscribe_device(worker.device_id)
            # 更新为未绑定状态
            worker.config["deviceId"] = ""
            worker.config["deviceName"] = ""
            worker.device_id = ""

        # 3. 更新本地配置（转为未绑定 key）
        self._config_mgr.remove_sensor(sensor_key)
        new_key = _make_sensor_key("", sensor_id)
        if worker:
            cfg = dict(worker.config)
            cfg["deviceId"] = ""
            self._config_mgr.add_sensor("", cfg)
            # 用新 key 重新注册 worker
            with self._lock:
                self._workers.pop(sensor_key, None)
                self._workers[new_key] = worker
            worker.sensor_key = new_key
            worker._label = _sensor_label(cfg)

        logger.info(f"传感器 {sensor_key} (DB id={sensor_id}) 已解绑 -> {new_key} (device_id=NULL, 保留在列表中)")
        return True

    def rebind_sensor(self, sensor_key: str, sensor_id: int,
                      new_device_id: str) -> Optional[str]:
        """
        换绑传感器到新设备: 在 DB 中更新 device_id，
        停止旧 worker，创建新 worker 并启动。

        Args:
            sensor_key: 当前传感器内部键
            sensor_id: 数据库传感器主键 ID
            new_device_id: 目标设备 device_id

        Returns:
            str: 新的 sensor_key，失败返回 None
        """
        if not self._db_client:
            logger.error("数据库客户端未初始化，无法执行换绑操作")
            return None

        # 验证目标设备存在
        if not self._db_client.device_exists(new_device_id):
            logger.error(f"目标设备不存在: {new_device_id}")
            return None

        # 1. 更新数据库: device_id = new_device_id
        db_ok = self._db_client.rebind_sensor(sensor_id, new_device_id)
        if not db_ok:
            logger.error(f"数据库换绑失败: sensor DB id={sensor_id} -> {new_device_id}")
            return None

        # 2. 停止旧 worker
        with self._lock:
            old_worker = self._workers.pop(sensor_key, None)
        if old_worker:
            old_worker.stop()
            self._mqtt_mgr.unsubscribe_device(old_worker.device_id)

        # 3. 从本地配置移除旧 key
        self._config_mgr.remove_sensor(sensor_key)

        # 4. 创建新 key 和配置
        new_key = _make_sensor_key(new_device_id, sensor_id)
        cfg = dict(old_worker.config) if old_worker else {
            "sensorId": sensor_id,
            "deviceId": new_device_id,
        }
        cfg["deviceId"] = new_device_id

        # 查询设备名
        devices = self._db_client.get_all_devices()
        for dev in devices:
            if dev.get("device_id") == new_device_id:
                cfg["deviceName"] = dev.get("name", "")
                cfg["location"] = dev.get("location", "")
                break

        # 保存到本地配置
        self._config_mgr.add_sensor(new_device_id, cfg)

        # 5. 创建新 worker
        sim_config = self._config_mgr.get_simulation_config()
        worker = SensorWorker(
            new_key, cfg, self._mqtt_mgr,
            sim_config=sim_config,
            on_publish=self._record_publish,
        )
        with self._lock:
            self._workers[new_key] = worker

        self._mqtt_mgr.subscribe_device(new_device_id)
        worker.start()

        logger.info(f"传感器 DB id={sensor_id} 已换绑: "
                    f"{sensor_key} -> {new_key} (device_id={new_device_id})")
        return new_key

    def sync_from_database(self) -> int:
        """
        [公开接口] 从数据库同步传感器（供 Flask API 调用）。
        遍历 DB 中所有 enabled 传感器，添加尚不在内存中的。
        """
        return self._load_from_database()
