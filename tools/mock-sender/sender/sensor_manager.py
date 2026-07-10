"""
传感器管理器 (v4 — 去设备化)
===========
管理所有模拟传感器的生命周期：添加、删除、启动、停止。
每个传感器在独立线程中按配置频率发布数据。

v4 变更:
  - 完全删除 deviceId 概念（传感器只认自己的 sensorId）
  - 控制指令通过 sensor cmd topic 接收: streetlight/sensor/{sensorId}/cmd
  - 控制响应通过 sensor cmd response topic 发送
  - 启动时订阅自己的 cmd 主题，不再订阅设备控制主题
"""

import logging
import threading
import time
from collections import deque
from datetime import datetime
from typing import Any, Callable, Dict, List, Optional

from sender.config_manager import ConfigManager, _make_sensor_key
from sender.data_generator import (
    generate_heartbeat,
    generate_sensor_data,
)
from sender.mqtt_client import MqttClientManager

logger = logging.getLogger("mock-sender.sensor")


def _sensor_label(cfg: Dict[str, Any]) -> str:
    sensor_name = cfg.get("displayName") or cfg.get("sensorType") or "传感器"
    tag = cfg.get("groupTag", "")
    if tag:
        return f"[{tag}] {sensor_name}"
    return sensor_name


class SensorWorker:
    """单个传感器的工作线程。定时发布传感器数据到 MQTT。

    v4: 传感器不持有 deviceId，控制指令通过自有的 cmd 主题接收。
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
    # v4: 传感器 cmd 主题订阅
    # ------------------------------------------------------------------

    def _subscribe_cmd(self) -> None:
        """订阅传感器自身的控制指令主题: streetlight/sensor/{sensorId}/cmd"""
        self._mqtt.subscribe_sensor_cmd(self.sensor_id)
        logger.info(f"传感器 [{self._label}] 已订阅 cmd 主题 (sensorId={self.sensor_id})")

    # ------------------------------------------------------------------
    # v4: 处理控制指令
    # ------------------------------------------------------------------

    def on_cmd(self, payload: Dict[str, Any]) -> None:
        """处理收到的控制指令（来自 streetlight/sensor/{sensorId}/cmd）。"""
        command = payload.get("command", "")
        source = payload.get("source", "manual")
        brightness = payload.get("brightness")

        if command == "on":
            self._light_status = "on"
            self.config["lightStatus"] = "on"
            if brightness is not None:
                self.config["brightness"] = brightness
        elif command == "off":
            self._light_status = "off"
            self.config["lightStatus"] = "off"
            self.config.pop("brightness", None)
        else:
            logger.warning(f"[{self._label}] 未知控制指令: {command}")
            return

        # manual 来源 → 手动模式；auto 来源 → 自动模式（恢复自动上报）
        if source == "manual":
            self._control_mode = "manual"
            self.config["controlMode"] = "manual"
        elif source == "auto":
            self._control_mode = "auto"
            self.config["controlMode"] = "auto"

        logger.info(
            f"[{self._label}] 收到控制指令: {command} (source={source}, brightness={brightness})"
        )

        # 发送响应
        response = {
            "command": command,
            "result": "success",
            "source": source,
            "sensorId": self.sensor_id,
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
        if brightness is not None:
            response["brightness"] = brightness
        self._mqtt.publish_cmd_response(self.sensor_id, response)

    # ------------------------------------------------------------------
    # 控制
    # ------------------------------------------------------------------

    def start(self) -> bool:
        if self._running:
            return False
        self._running = True
        self._start_time = time.time()
        # v4: 从 config 恢复运行状态（防止 stop+start 后仍保留上次手动模式的状态）
        self._control_mode = self.config.get("controlMode", "auto")
        self._light_status = self.config.get("lightStatus", "off")
        self._subscribe_cmd()
        self._thread = threading.Thread(
            target=self._run_loop,
            name=f"sensor-{self.sensor_key}",
            daemon=True,
        )
        self._thread.start()
        logger.info(f"传感器 {self._label} 已启动 (interval={self.interval}s, mode={self._control_mode})")
        return True

    def stop(self) -> None:
        self._running = False
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=3)
        # v4: 停止时取消 cmd 订阅
        self._mqtt.unsubscribe_sensor_cmd(self.sensor_id)
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
        """执行一次传感器数据发布（v4: 不再携带 deviceId）。"""
        now = time.time()
        import json as _json

        data_range = self.config.get("dataRange", {"min": 0, "max": 800})
        brightness = self.config.get("brightness")
        my_sensor_type = self.sensor_type
        my_sensor_id = self.sensor_id
        actual_topic = f"streetlight/sensor/{my_sensor_id}/data"

        extra = {
            "controlMode": self._control_mode,
            "sensorType": self.sensor_type,
            "displayName": self.display_name,
        }
        group_tag = self.config.get("groupTag", "")
        if group_tag:
            extra["groupTag"] = group_tag

        # v4: 生成数据时不再传 device_id
        base_payload = generate_sensor_data(
            light_status=self._light_status,
            data_range=data_range,
            extra_fields=extra,
            brightness=brightness,
            sim_config=self._sim_config,
            sensor_type=my_sensor_type,
            sensor_id=my_sensor_id,
        )

        send_mode = self.config.get("autoSendMode", "algorithm")

        if send_mode == "fixed":
            fixed_content = self.config.get("autoSendContent", "")
            if fixed_content and fixed_content.strip():
                from datetime import datetime as dt, timezone
                ts = dt.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
                content = fixed_content.replace("{{timestamp}}", ts)
                try:
                    overrides = _json.loads(content)
                    base_payload.update(overrides)
                    if "illuminance" in overrides and "lightIntensity" not in overrides:
                        base_payload["lightIntensity"] = overrides["illuminance"]
                    elif "lightIntensity" in overrides and "illuminance" not in overrides:
                        base_payload["illuminance"] = overrides["lightIntensity"]
                    logger.info(f"[{self._label}] 固定模式发送: illuminance={base_payload.get('illuminance')}, "
                                f"sensorType={base_payload.get('sensorType')}")
                except (_json.JSONDecodeError, TypeError) as e:
                    logger.warning(f"[{self._label}] 固定内容 JSON 格式错误: {e}")

        elif send_mode == "template":
            message_template = self.config.get("messageTemplate", "")
            if message_template and message_template.strip():
                from sender.data_generator import generate_sensor_data_with_template
                msg_str = generate_sensor_data_with_template(
                    template=message_template,
                    light_status=self._light_status,
                    data_range=data_range,
                    extra_fields=extra,
                    brightness=brightness,
                    sim_config=self._sim_config,
                    sensor_type=my_sensor_type,
                    sensor_id=my_sensor_id,
                )
                try:
                    overrides = _json.loads(msg_str)
                    base_payload.update(overrides)
                except (_json.JSONDecodeError, TypeError) as e:
                    logger.warning(f"[{self._label}] 模板渲染格式错误: {e}")

        ok = self._mqtt.publish_sensor_data(my_sensor_id, base_payload)
        if ok:
            self.publish_count += 1
            self.last_publish_time = now
            logger.debug(f"[{self._label}] 数据已发送: illuminance={base_payload.get('illuminance')}, "
                         f"status={base_payload.get('status')}")
            if self._on_publish:
                self._on_publish(
                    self.sensor_key, my_sensor_id,
                    self.display_name, actual_topic, base_payload,
                )
        else:
            logger.warning(f"[{self._label}] 数据发送失败（MQTT可能未连接）")
        return ok

    def _run_loop(self) -> None:
        last_heartbeat_time = 0.0
        heartbeat_interval = 10

        while self._running:
            now = time.time()

            # ★ 无论什么模式都持续上报数据（监控需要），模式只影响灯状态逻辑
            try:
                self._do_publish()
            except Exception as e:
                logger.error(f"[{self._label}] 数据发布异常: {e}", exc_info=True)

            # 传感器心跳（v4: 使用 sensor_id，不携带 deviceId）
            try:
                if now - last_heartbeat_time >= heartbeat_interval:
                    hb_payload = generate_heartbeat(self.sensor_id)
                    hb_payload["sensorKey"] = self.sensor_key
                    hb_payload["sensorId"] = self.sensor_id
                    if self._mqtt.publish_heartbeat(self.sensor_id, hb_payload):
                        self.heartbeat_count += 1
                    last_heartbeat_time = now
            except Exception as e:
                logger.error(f"[{self._label}] 心跳发送异常: {e}")

            time.sleep(self.interval)


class SensorManager:
    """传感器管理器 (v4)。"""

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
        with self._lock:
            sensor_id = sensor_cfg.get("sensorId", int(time.time() * 1000) % 100000)
            key = _make_sensor_key(sensor_id)
            if key in self._workers:
                logger.warning(f"传感器 {key} 已存在，跳过")
                return None
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

        # MQTT 注册（v4: 不携带 deviceId）
        sensor_info = {
            "sensorId": sensor_id,
            "sensorType": cfg.get("sensorType", "light"),
            "displayName": cfg.get("displayName", ""),
            "dataTopic": f"streetlight/sensor/{sensor_id}/data",
            "reportFrequency": cfg.get("interval", 5),
            "configJson": cfg.get("configJson", ""),
            "enabled": True,
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
        self._mqtt_mgr.publish_sensor_register(sensor_info)
        return saved_key

    def remove_sensor(self, sensor_key: str) -> bool:
        with self._lock:
            worker = self._workers.pop(sensor_key, None)
            if not worker:
                return False
        worker.stop()
        self._config_mgr.remove_sensor(sensor_key)
        logger.info(f"已移除传感器: {_sensor_label(worker.config)} (key={sensor_key})")
        self._mqtt_mgr.publish_sensor_unregister(worker.sensor_id)
        return True

    # ------------------------------------------------------------------
    # v4: 传感器 cmd 指令处理
    # ------------------------------------------------------------------

    def on_sensor_cmd(self, sensor_id: int, payload: Dict[str, Any]) -> None:
        """分发控制指令到对应 sensorId 的 worker。"""
        key = _make_sensor_key(sensor_id)
        with self._lock:
            worker = self._workers.get(key)
        if worker:
            worker.on_cmd(payload)
        else:
            logger.warning(f"收到未知传感器 {sensor_id} 的控制指令，忽略")

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
    # 批量加载
    # ------------------------------------------------------------------

    def load_from_config(self) -> int:
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
        with self._lock:
            keys = list(self._workers.keys())
        for key in keys:
            worker = self._workers.pop(key, None)
            if worker:
                worker.stop()
        logger.info(f"已关闭 {len(keys)} 个传感器工作线程（配置已保留）")

    def re_register_all(self) -> int:
        count = 0
        with self._lock:
            workers = list(self._workers.items())
        for key, worker in workers:
            cfg = worker.config
            sensor_info = {
                "sensorId": worker.sensor_id,
                "sensorType": worker.sensor_type,
                "displayName": worker.display_name,
                "dataTopic": f"streetlight/sensor/{worker.sensor_id}/data",
                "reportFrequency": worker.interval,
                "configJson": cfg.get("configJson", ""),
                "enabled": True,
                "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            }
            if self._mqtt_mgr.publish_sensor_register(sensor_info):
                count += 1
        logger.info(f"MQTT 重连后已重新注册 {count} 个传感器")
        return count
