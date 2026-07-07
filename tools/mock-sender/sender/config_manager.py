"""
配置管理模块
===========
管理 MQTT Broker 连接参数和传感器配置的加载、保存与动态更新。
"""

import json
import os
import threading
from typing import Any, Dict, Optional

DEFAULT_CONFIG_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "config.json")

DEFAULT_CONFIG: Dict[str, Any] = {
    "mqtt": {
        "broker": "8.130.102.89",
        "port": 1883,
        "username": "jieshou",
        "password": "jieshou123",
        "topicPrefix": "streetlight",
        "clientId": "mock-sender-v2",
    },
    "sensors": {},
}


class ConfigManager:
    """配置管理器，线程安全地读写 MQTT 及传感器配置。"""

    def __init__(self, config_path: Optional[str] = None):
        self._lock = threading.Lock()
        self._config_path = config_path or DEFAULT_CONFIG_PATH
        self._config: Dict[str, Any] = self._load()

    # ------------------------------------------------------------------
    # 内部加载 / 保存
    # ------------------------------------------------------------------

    def _load(self) -> Dict[str, Any]:
        """从 JSON 文件加载配置，文件不存在时返回默认配置。"""
        if not os.path.exists(self._config_path):
            return dict(DEFAULT_CONFIG)
        try:
            with open(self._config_path, "r", encoding="utf-8") as f:
                cfg = json.load(f)
            # 合并缺失的默认字段
            for key in DEFAULT_CONFIG:
                cfg.setdefault(key, dict(DEFAULT_CONFIG[key]))
            return cfg
        except (json.JSONDecodeError, OSError) as e:
            print(f"[Config] 配置文件加载失败 ({e})，使用默认配置")
            return dict(DEFAULT_CONFIG)

    def save(self) -> bool:
        """将当前配置写入 JSON 文件。"""
        try:
            with open(self._config_path, "w", encoding="utf-8") as f:
                json.dump(self._config, f, ensure_ascii=False, indent=2)
            return True
        except OSError as e:
            print(f"[Config] 保存配置失败: {e}")
            return False

    # ------------------------------------------------------------------
    # MQTT 配置
    # ------------------------------------------------------------------

    def get_mqtt_config(self) -> Dict[str, Any]:
        with self._lock:
            return dict(self._config.get("mqtt", {}))

    def update_mqtt_config(self, updates: Dict[str, Any]) -> bool:
        """更新 MQTT 连接参数（仅更新传入的字段）。"""
        with self._lock:
            mqtt_cfg = self._config.setdefault("mqtt", {})
            mqtt_cfg.update(updates)
            return self.save()

    # ------------------------------------------------------------------
    # 传感器配置
    # ------------------------------------------------------------------

    def get_all_sensors(self) -> Dict[str, Any]:
        with self._lock:
            return dict(self._config.get("sensors", {}))

    def get_sensor(self, device_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            return self._config.get("sensors", {}).get(device_id)

    def add_sensor(self, device_id: str, sensor_cfg: Dict[str, Any]) -> bool:
        """添加一个传感器，已存在则返回 False。"""
        with self._lock:
            sensors = self._config.setdefault("sensors", {})
            if device_id in sensors:
                return False
            # 填充默认字段
            default_sensor = {
                "name": device_id,
                "location": "",
                "enabled": True,
                "interval": 5,
                "dataRange": {"min": 0, "max": 800},
                "lightStatus": "off",
                "controlMode": "auto",
            }
            default_sensor.update(sensor_cfg)
            sensors[device_id] = default_sensor
            return self.save()

    def update_sensor(self, device_id: str, updates: Dict[str, Any]) -> bool:
        """更新指定传感器的字段。"""
        with self._lock:
            sensors = self._config.get("sensors", {})
            if device_id not in sensors:
                return False
            sensors[device_id].update(updates)
            return self.save()

    def remove_sensor(self, device_id: str) -> bool:
        """删除一个传感器。"""
        with self._lock:
            sensors = self._config.get("sensors", {})
            if device_id not in sensors:
                return False
            del sensors[device_id]
            return self.save()

    # ------------------------------------------------------------------
    # 辅助
    # ------------------------------------------------------------------

    def get_broker_url(self) -> str:
        mqtt = self.get_mqtt_config()
        return f"{mqtt['broker']}:{mqtt['port']}"

    def reload(self) -> None:
        """从磁盘重新加载配置（外部修改时使用）。"""
        with self._lock:
            self._config = self._load()
