"""
传感器管理器 (v3)
===========
管理所有模拟传感器的生命周期：添加、删除、启动、停止。
每个传感器在独立线程中按配置频率发布数据。

v3 变更:
  - 传感器完全独立，不持有后端设备 ID
  - 内部键格式: sensor_{sensorId}
  - MQTT 注册/数据使用传感器自身 sensorId，不携带 deviceId
  - 设备绑定通过 MQTT 配置指令 (bind_to_device / unbind_from_device) 实现
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
    """生成传感器可读标签: 传感器名"""
    sensor_name = cfg.get("displayName") or cfg.get("sensorType") or "传感器"
    tag = cfg.get("groupTag", "")
    if tag:
        return f"[{tag}] {sensor_name}"
    return sensor_name


class SensorWorker:
    """单个传感器的工作线程。定时发布传感器数据到 MQTT。"""

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
        self.sensor_id = config.get("sensorId", 0)
        self.config = dict(config)
        self._mqtt = mqtt_client
        self._sim_config = sim_config or {}
        self._on_stopped = on_stopped
        self._on_publish = on_publish

        # 运行时状态
        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._start_time = time.time()

        self._light_status = config.get("lightStatus", "off")
        self._control_mode = config.get("controlMode", "auto")

        # 设备绑定追踪（由 bind_to_device / unbind_from_device 控制）
        self._bound_device_id: str = ""
        self._subscribed_device: str = ""

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

    # ------------------------------------------------------------------
    # 设备绑定控制
    # ------------------------------------------------------------------

    def bind_to_device(self, device_id: str) -> None:
        """绑定到设备：订阅设备控制主题。"""
        if self._bound_device_id == device_id and self._subscribed_device == device_id:
            return
        self._bound_device_id = device_id
        self._mqtt.subscribe_device(device_id)
        self._subscribed_device = device_id
        logger.info(f"传感器 [{self._label}] 已绑定到设备 {device_id}")

    def unbind_from_device(self) -> None:
        """从设备解绑：取消控制订阅。"""
        if self._subscribed_device:
            self._mqtt.unsubscribe_device(self._subscribed_device)
            logger.info(f"传感器 [{self._label}] 已从设备 {self._subscribed_device} 解绑")
            self._subscribed_device = ""
            self._bound_device_id = ""

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
        if "controlMode" in updates:
            self._control_mode = updates["controlMode"]
        if "lightStatus" in updates:
            self._light_status = updates["lightStatus"]
        new_mode = self._control_mode
        if "controlMode" in updates and old_mode != new_mode:
            logger.info(f"传感器 [{self._label}] 模式切换: {old_mode} → {new_mode}")

    def set_light_status(self, status: str) -> None:
        self._light_status = status

    def set_control_mode(self, mode: str) -> None:
        self._control_mode = mode

    def publish_once(self) -> bool:
        return self._do_publish()

    # ------------------------------------------------------------------
    # 内部循环
    # ------------------------------------------------------------------

    def _do_publish(self) -> bool:
        """执行一次传感器数据发布，使用传感器自身 sensorId。"""
        now = time.time()
        import json as _json

        data_range = self.config.get("dataRange", {"min": 0, "max": 800})
        data_topic = self.config.get("dataTopic", "")
        brightness = self.config.get("brightness")
        my_sensor_type = self.sensor_type
        my_sensor_id = self.sensor_id
        actual_topic = data_topic or f"streetlight/sensor/{my_sensor_id}/data"

        extra = {
            "controlMode": self._control_mode,
            "sensorType": self.sensor_type,
            "displayName": self.display_name,
        }
        group_tag = self.config.get("groupTag", "")
        if group_tag:
            extra["groupTag"] = group_tag

        send_mode = self.config.get("autoSendMode", "algorithm")

        if send_mode == "fixed":
            fixed_content = self.config.get("autoSendContent", "")
            if fixed_content and fixed_content.strip():
                from datetime import datetime as dt, timezone
                ts = dt.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
                content = fixed_content.replace("{{timestamp}}", ts)
                content = content.replace("{{deviceId}}", self._bound_device_id)
                try:
                    payload = _json.loads(content)
                except (_json.JSONDecodeError, TypeError):
                    logger.warning(f"[{self._label}] 固定内容格式错误，回退到算法模式")
                    payload = generate_sensor_data(
                        device_id=self._bound_device_id,
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
                    device_id=self._bound_device_id,
                    light_status=self._light_status,
                    data_range=data_range,
                    extra_fields=extra,
                    brightness=brightness,
                    sim_config=self._sim_config,
                    sensor_type=my_sensor_type,
                    sensor_id=my_sensor_id,
                )
        else:
            message_template = self.config.get("messageTemplate", "")
            if message_template and message_template.strip():
                from sender.data_generator import generate_sensor_data_with_template
                msg_str = generate_sensor_data_with_template(
                    template=message_template,
                    device_id=self._bound_device_id,
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
                        device_id=self._bound_device_id,
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
                    device_id=self._bound_device_id,
                    light_status=self._light_status,
                    data_range=data_range,
                    extra_fields=extra,
                    brightness=brightness,
                    sim_config=self._sim_config,
                    sensor_type=my_sensor_type,
                    sensor_id=my_sensor_id,
                )

        ok = self._mqtt.publish_sensor_data(my_sensor_id, payload, data_topic)
        if ok:
            self.publish_count += 1
            self.last_publish_time = now
            if self._on_publish:
                self._on_publish(
                    self.sensor_key, my_sensor_id,
                    self.display_name, actual_topic, payload,
                )
        return ok

    def _run_loop(self) -> None:
        last_heartbeat_time = 0.0
        heartbeat_interval = 10

        while self._running:
            now = time.time()

            if self._control_mode == 'manual':
                time.sleep(0.5)
                continue

            self._do_publish()

            # 传感器心跳（总是发送，使用自身 sensorId）
            if now - last_heartbeat_time >= heartbeat_interval:
                hb_payload = generate_heartbeat(self._bound_device_id or f"sensor_{self.sensor_id}")
                hb_payload["sensorKey"] = self.sensor_key
                hb_payload["sensorId"] = self.sensor_id
                if self._mqtt.publish_heartbeat(self.sensor_id, hb_payload):
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

        self._history: deque = deque(maxlen=200)
        self._history_lock = threading.Lock()

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
                    "sensorId": cfg.get("sensorId", ""),
                    "displayName": worker.display_name,
                    "sensorType": worker.sensor_type,
                    "groupTag": cfg.get("groupTag", ""),
                    "dataTopic": cfg.get("dataTopic", ""),
                    "boundDeviceId": worker._bound_device_id or "",
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
                "sensorId": cfg.get("sensorId", ""),
                "displayName": worker.display_name,
                "sensorType": worker.sensor_type,
                "groupTag": cfg.get("groupTag", ""),
                "dataTopic": cfg.get("dataTopic", ""),
                "boundDeviceId": worker._bound_device_id or "",
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

    def add_sensor(self, sensor_cfg: Dict[str, Any]) -> Optional[str]:
        """添加独立传感器并自动启动（v3: 不绑定设备）。"""
        with self._lock:
            sensor_id = sensor_cfg.get("sensorId", int(time.time() * 1000) % 100000)
            key = _make_sensor_key(sensor_id)

            if key in self._workers:
                logger.warning(f"传感器 {key} 已存在，跳过")
                return None

            # 保存到配置
            saved_key = self._config_mgr.add_sensor(sensor_cfg)
            if not saved_key:
                return None

            cfg = self._config_mgr.get_sensor(saved_key)
            if not cfg:
                return None

            worker = SensorWorker(saved_key, cfg, self._mqtt_mgr,
                                  sim_config=self._config_mgr.get_simulation_config(),
                                  on_publish=self._record_publish)
            self._workers[saved_key] = worker

        worker.start()
        logger.info(f"已添加传感器: {_sensor_label(cfg)} (key={saved_key})")

        # MQTT 全局注册（v3: 不携带 deviceId）
        sensor_info = {
            "sensorId": sensor_id,
            "sensorType": cfg.get("sensorType", "light"),
            "displayName": cfg.get("displayName", ""),
            "dataTopic": cfg.get("dataTopic", ""),
            "reportFrequency": cfg.get("interval", 5),
            "configJson": cfg.get("configJson", ""),
            "enabled": True,
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
        self._mqtt_mgr.publish_sensor_register(sensor_info)

        return saved_key

    def remove_sensor(self, sensor_key: str) -> bool:
        """停止并移除传感器，通过 MQTT 发布注销。"""
        with self._lock:
            worker = self._workers.pop(sensor_key, None)
            if not worker:
                return False

        worker.unbind_from_device()
        worker.stop()
        self._config_mgr.remove_sensor(sensor_key)
        logger.info(f"已移除传感器: {_sensor_label(worker.config)} (key={sensor_key})")

        # MQTT 注销
        self._mqtt_mgr.publish_sensor_unregister(worker.sensor_id)

        return True

    # ------------------------------------------------------------------
    # 设备绑定 / 解绑（由后端 MQTT 配置指令触发）
    # ------------------------------------------------------------------

    def bind_to_device(self, sensor_id: int, device_id: str) -> bool:
        """将传感器绑定到指定设备：订阅设备控制主题。"""
        key = _make_sensor_key(sensor_id)
        with self._lock:
            worker = self._workers.get(key)
            if not worker:
                logger.warning(f"传感器 {key} 不存在，无法绑定到设备 {device_id}")
                return False
            worker.bind_to_device(device_id)
        logger.info(f"传感器 {key} 已绑定到设备 {device_id}")
        return True

    def unbind_from_device(self, sensor_id: int) -> bool:
        """将传感器从设备解绑：取消控制订阅。"""
        key = _make_sensor_key(sensor_id)
        with self._lock:
            worker = self._workers.get(key)
            if not worker:
                logger.warning(f"传感器 {key} 不存在，无法解绑")
                return False
            worker.unbind_from_device()
        logger.info(f"传感器 {key} 已解绑")
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
            logger.info(f"传感器 {_sensor_label(worker.config)} 配置已更新")
            return True

    # ------------------------------------------------------------------
    # 控制指令处理
    # ------------------------------------------------------------------

    def handle_control_command(self, device_id: str, payload: Dict[str, Any]) -> None:
        """处理下发的开关灯控制指令（作用于绑定到此设备的所有传感器）。"""
        if not device_id:
            logger.debug("忽略空 deviceId 的控制指令")
            return

        command = payload.get("command", "")
        source = payload.get("source", "manual")
        brightness = payload.get("brightness")

        affected = []
        with self._lock:
            for sensor_key, worker in self._workers.items():
                if worker._bound_device_id == device_id:
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

                    new_mode = "manual" if source == "manual" else "auto"
                    old_mode = worker.control_mode
                    if old_mode != new_mode:
                        worker.set_control_mode(new_mode)
                        worker.config["controlMode"] = new_mode
                        logger.info(f"传感器 [{_sensor_label(worker.config)}] 模式切换: "
                                    f"{old_mode} → {new_mode} (来源: {source})")

                    affected.append(sensor_key)

        if not affected:
            logger.warning(f"控制指令: 设备 {device_id} 无绑定传感器")
            return

        brightness_info = f", 亮度={brightness}%" if brightness is not None else ""
        logger.info(f"设备 {device_id} 灯光 -> {command}{brightness_info} "
                    f"(来源: {source}, 影响 {len(affected)} 个传感器)")

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
                worker.start()
                count += 1
                logger.info(f"[本地加载] {_sensor_label(cfg)} (key={sensor_key})")
        logger.info(f"从本地配置加载了 {count} 个传感器")
        return count

    # ------------------------------------------------------------------
    # 发送历史
    # ------------------------------------------------------------------

    def _record_publish(self, sensor_key: str, sensor_id: int,
                        display_name: str, topic: str,
                        payload: Dict[str, Any]) -> None:
        """记录一次成功发送到历史队列。"""
        try:
            with self._history_lock:
                self._history.append({
                    "time": datetime.now().strftime("%H:%M:%S"),
                    "sensorKey": sensor_key,
                    "sensorId": sensor_id,
                    "displayName": display_name,
                    "topic": topic,
                    "payload": payload,
                })
        except Exception:
            pass

    def get_history(self, filter_key: str = "") -> List[Dict[str, Any]]:
        with self._history_lock:
            history = list(self._history)
        if filter_key:
            history = [h for h in history if h["sensorKey"] == filter_key]
        return list(reversed(history))

    def clear_history(self) -> None:
        with self._history_lock:
            self._history.clear()
        logger.info("发送历史已清空")

    # ------------------------------------------------------------------
    # 批量启停
    # ------------------------------------------------------------------

    def stop_all_sending(self) -> int:
        count = 0
        with self._lock:
            for worker in self._workers.values():
                if worker.is_running:
                    worker.stop()
                    count += 1
        logger.info(f"已停止 {count} 个传感器的发送")
        return count

    def start_all(self) -> int:
        count = 0
        with self._lock:
            for worker in self._workers.values():
                if not worker.is_running:
                    if worker.start():
                        count += 1
        logger.info(f"已启动 {count} 个传感器")
        return count

    def publish_once(self, sensor_key: str) -> bool:
        with self._lock:
            worker = self._workers.get(sensor_key)
            if not worker:
                return False
        return worker.publish_once()

    def stop_all(self) -> None:
        with self._lock:
            keys = list(self._workers.keys())
        for key in keys:
            self.remove_sensor(key)
        logger.info("已停止所有传感器")

    def shutdown(self) -> None:
        """关闭所有传感器工作线程但不删除配置（用于进程退出时的安全清理）。"""
        with self._lock:
            keys = list(self._workers.keys())
        for key in keys:
            worker = self._workers.pop(key, None)
            if worker:
                worker.unbind_from_device()
                worker.stop()
        logger.info(f"已关闭 {len(keys)} 个传感器工作线程（配置已保留）")

    def re_register_all(self) -> int:
        """重新注册所有运行中的传感器到 MQTT（用于连接/重连时恢复注册）。
        确保后端无论何时启动都能发现模拟器中已存在的传感器。"""
        count = 0
        with self._lock:
            workers = list(self._workers.items())
        for key, worker in workers:
            cfg = worker.config
            sensor_info = {
                "sensorId": worker.sensor_id,
                "sensorType": worker.sensor_type,
                "displayName": worker.display_name,
                "dataTopic": cfg.get("dataTopic", ""),
                "reportFrequency": worker.interval,
                "configJson": cfg.get("configJson", ""),
                "enabled": True,
                "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            }
            if self._mqtt_mgr.publish_sensor_register(sensor_info):
                count += 1
        logger.info(f"MQTT 重连后已重新注册 {count} 个传感器")
        return count
