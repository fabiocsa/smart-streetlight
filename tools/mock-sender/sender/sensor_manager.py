"""
传感器管理器
===========
管理所有模拟传感器的生命周期：添加、删除、启动、停止。
每个传感器在独立线程中按配置频率发布数据和心跳。
"""

import logging
import threading
import time
from datetime import datetime
from typing import Any, Callable, Dict, List, Optional

from sender.config_manager import ConfigManager
from sender.data_generator import (
    generate_control_response,
    generate_heartbeat,
    generate_sensor_data,
)
from sender.mqtt_client import MqttClientManager

logger = logging.getLogger("mock-sender.sensor")


class SensorWorker:
    """
    单个传感器的工作线程。
    定时发布传感器数据和心跳到 MQTT。
    """

    def __init__(
        self,
        device_id: str,
        config: Dict[str, Any],
        mqtt_client: MqttClientManager,
        on_stopped: Optional[Callable] = None,
    ):
        self.device_id = device_id
        self.config = dict(config)
        self._mqtt = mqtt_client
        self._on_stopped = on_stopped

        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._start_time = time.time()

        # 内部状态
        self._light_status = config.get("lightStatus", "off")
        self._control_mode = config.get("controlMode", "auto")

        # 统计
        self.publish_count = 0
        self.heartbeat_count = 0
        self.last_publish_time: Optional[float] = None

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

    # ------------------------------------------------------------------
    # 控制
    # ------------------------------------------------------------------

    def start(self) -> bool:
        """启动传感器线程。"""
        if self._running:
            return False
        self._running = True
        self._start_time = time.time()
        self._thread = threading.Thread(
            target=self._run_loop,
            name=f"sensor-{self.device_id}",
            daemon=True,
        )
        self._thread.start()
        logger.info(f"传感器 {self.device_id} 已启动 (interval={self.interval}s)")
        return True

    def stop(self) -> None:
        """停止传感器线程。"""
        self._running = False
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=3)
        logger.info(f"传感器 {self.device_id} 已停止")
        if self._on_stopped:
            self._on_stopped(self.device_id)

    def update_config(self, updates: Dict[str, Any]) -> None:
        """动态更新配置（频率、数据范围等）。"""
        self.config.update(updates)

    def set_light_status(self, status: str) -> None:
        """设置灯光状态。"""
        self._light_status = status

    def set_control_mode(self, mode: str) -> None:
        """设置控制模式。"""
        self._control_mode = mode

    # ------------------------------------------------------------------
    # 内部循环
    # ------------------------------------------------------------------

    def _run_loop(self) -> None:
        """传感器主循环：定时发布数据和心跳。"""
        last_heartbeat_time = 0.0
        heartbeat_interval = 10  # 每 10 秒发一次心跳

        while self._running:
            now = time.time()
            elapsed = now - self._start_time

            # 发布传感器数据
            data_range = self.config.get("dataRange", {"min": 0, "max": 800})
            extra = {
                "location": self.config.get("location", ""),
                "controlMode": self._control_mode,
            }
            payload = generate_sensor_data(
                self.device_id, self._light_status, elapsed, data_range, extra
            )
            ok = self._mqtt.publish_sensor_data(self.device_id, payload)
            if ok:
                self.publish_count += 1
                self.last_publish_time = now

            # 定时发布心跳
            if now - last_heartbeat_time >= heartbeat_interval:
                hb_payload = generate_heartbeat(self.device_id)
                if self._mqtt.publish_heartbeat(self.device_id, hb_payload):
                    self.heartbeat_count += 1
                last_heartbeat_time = now

            # 等待下一个周期
            time.sleep(self.interval)


class SensorManager:
    """
    传感器管理器（单例），管理所有 SensorWorker。
    提供线程安全的添加 / 删除 / 启停操作。
    """

    def __init__(self, config_mgr: ConfigManager, mqtt_mgr: MqttClientManager):
        self._config_mgr = config_mgr
        self._mqtt_mgr = mqtt_mgr
        self._workers: Dict[str, SensorWorker] = {}
        self._lock = threading.Lock()

    # ------------------------------------------------------------------
    # 传感器列表
    # ------------------------------------------------------------------

    def list_sensors(self) -> List[Dict[str, Any]]:
        """返回所有传感器的摘要信息。"""
        result = []
        with self._lock:
            for device_id, worker in self._workers.items():
                cfg = worker.config
                result.append({
                    "deviceId": device_id,
                    "name": cfg.get("name", device_id),
                    "location": cfg.get("location", ""),
                    "running": worker.is_running,
                    "interval": worker.interval,
                    "lightStatus": worker.light_status,
                    "controlMode": worker.control_mode,
                    "publishCount": worker.publish_count,
                    "heartbeatCount": worker.heartbeat_count,
                    "uptime": round(worker.uptime, 1),
                    "lastPublish": worker.last_publish_time,
                })
        return result

    def get_sensor(self, device_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            worker = self._workers.get(device_id)
            if not worker:
                return None
            return {
                "deviceId": device_id,
                "name": worker.config.get("name", device_id),
                "location": worker.config.get("location", ""),
                "running": worker.is_running,
                "interval": worker.interval,
                "lightStatus": worker.light_status,
                "controlMode": worker.control_mode,
                "publishCount": worker.publish_count,
                "heartbeatCount": worker.heartbeat_count,
                "uptime": round(worker.uptime, 1),
                "lastPublish": worker.last_publish_time,
                "dataRange": worker.config.get("dataRange", {"min": 0, "max": 800}),
            }

    # ------------------------------------------------------------------
    # 添加 / 删除
    # ------------------------------------------------------------------

    def add_sensor(self, device_id: str, sensor_cfg: Dict[str, Any]) -> bool:
        """添加传感器并自动启动。"""
        with self._lock:
            if device_id in self._workers:
                return False

            # 保存到配置
            self._config_mgr.add_sensor(device_id, sensor_cfg)
            cfg = self._config_mgr.get_sensor(device_id)
            if not cfg:
                return False

            # 创建 Worker
            worker = SensorWorker(device_id, cfg, self._mqtt_mgr)
            self._workers[device_id] = worker

        # 订阅控制主题
        self._mqtt_mgr.subscribe_device(device_id)

        # 启动
        worker.start()
        logger.info(f"已添加传感器: {device_id}")
        return True

    def remove_sensor(self, device_id: str) -> bool:
        """停止并移除传感器。"""
        with self._lock:
            worker = self._workers.pop(device_id, None)
            if not worker:
                return False

        worker.stop()
        self._mqtt_mgr.unsubscribe_device(device_id)
        self._config_mgr.remove_sensor(device_id)
        logger.info(f"已移除传感器: {device_id}")
        return True

    # ------------------------------------------------------------------
    # 启停
    # ------------------------------------------------------------------

    def start_sensor(self, device_id: str) -> bool:
        """启动一个已添加的传感器。"""
        with self._lock:
            worker = self._workers.get(device_id)
            if not worker:
                return False
            return worker.start()

    def stop_sensor(self, device_id: str) -> bool:
        """停止一个传感器（不移除）。"""
        with self._lock:
            worker = self._workers.get(device_id)
            if not worker:
                return False
            worker.stop()
            return True

    # ------------------------------------------------------------------
    # 配置更新
    # ------------------------------------------------------------------

    def update_sensor_config(self, device_id: str, updates: Dict[str, Any]) -> bool:
        """更新传感器配置（如 frequency, dataRange 等）。"""
        with self._lock:
            worker = self._workers.get(device_id)
            if not worker:
                return False
            worker.update_config(updates)
            self._config_mgr.update_sensor(device_id, updates)
            logger.info(f"传感器 {device_id} 配置已更新: {updates}")
            return True

    # ------------------------------------------------------------------
    # 控制指令处理
    # ------------------------------------------------------------------

    def handle_control_command(self, device_id: str, payload: Dict[str, Any]) -> None:
        """处理下发的开关灯控制指令。"""
        command = payload.get("command", "")
        source = payload.get("source", "manual")

        with self._lock:
            worker = self._workers.get(device_id)
            if not worker:
                logger.warning(f"控制指令: 未知设备 {device_id}")
                return

            if command == "on":
                worker.set_light_status("on")
            elif command == "off":
                worker.set_light_status("off")
            else:
                logger.warning(f"未知控制指令: {command}")
                return

        logger.info(f"设备 {device_id} 灯光 -> {command} (来源: {source})")

        # 回复执行结果
        response = generate_control_response(device_id, command, "success")
        response["source"] = source
        self._mqtt_mgr.publish_control_response(device_id, response)

    # ------------------------------------------------------------------
    # 批量加载
    # ------------------------------------------------------------------

    def load_from_config(self) -> int:
        """从配置文件中加载所有 enabled 的传感器。"""
        sensors = self._config_mgr.get_all_sensors()
        count = 0
        for device_id, cfg in sensors.items():
            if cfg.get("enabled", True) and device_id not in self._workers:
                worker = SensorWorker(device_id, cfg, self._mqtt_mgr)
                with self._lock:
                    self._workers[device_id] = worker
                self._mqtt_mgr.subscribe_device(device_id)
                worker.start()
                count += 1
        logger.info(f"从配置加载了 {count} 个传感器")
        return count

    def stop_all(self) -> None:
        """停止所有传感器。"""
        with self._lock:
            ids = list(self._workers.keys())
        for did in ids:
            self.remove_sensor(did)
        logger.info("已停止所有传感器")
