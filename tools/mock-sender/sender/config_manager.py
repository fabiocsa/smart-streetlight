"""
配置管理模块
===========
管理 MQTT Broker 连接参数和传感器配置的加载、保存与动态更新。
传感器内部键格式: {deviceId}_{sensorId} (如 SL-001_1)
"""

import json
import os
import threading
import time
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
    "backendUrl": "http://localhost:8080/api",
    "device": {
        "deviceId": "SL-001",
        "name": "路灯A-01",
        "location": "校门口",
    },
    "simulation": {
        "latitude": 29.5,
        "longitude": 106.5,
        "timezoneOffset": 8,
        "cloudCoverMax": 0.8,
        "cloudChangeInterval": 1800,
        "temperature": {
            "annualMean": 21.5,
            "annualAmplitude": 13.5,
            "dailyAmplitude": 5.0
        }
    },
    "sensors": {},
}


def _make_sensor_key(device_id, sensor_id) -> str:
    """生成传感器内部唯一键: {deviceId}_{sensorId}。
    device_id 为空/None 时返回 unbound_{sensorId}。"""
    if device_id:
        return f"{device_id}_{sensor_id}"
    return f"unbound_{sensor_id}"


class ConfigManager:
    """配置管理器，线程安全地读写 MQTT 及传感器配置。"""

    def __init__(self, config_path: Optional[str] = None):
        self._lock = threading.Lock()
        self._config_path = config_path or DEFAULT_CONFIG_PATH
        self._config: Dict[str, Any] = self._load()
        self._migrate_old_keys()

    # ------------------------------------------------------------------
    # 内部加载 / 保存 / 迁移
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
                if key not in cfg:
                    cfg[key] = dict(DEFAULT_CONFIG[key]) if isinstance(DEFAULT_CONFIG[key], dict) else DEFAULT_CONFIG[key]
            return cfg
        except (json.JSONDecodeError, OSError) as e:
            print(f"[Config] 配置文件加载失败 ({e})，使用默认配置")
            return dict(DEFAULT_CONFIG)

    def _migrate_old_keys(self) -> bool:
        """将旧版 {deviceId: config} 键迁移为新版 {deviceId}_{sensorId} 键。"""
        with self._lock:
            sensors = self._config.setdefault("sensors", {})
            migrated = {}
            has_old = False
            for key, cfg in list(sensors.items()):
                if "_" in key and not key.startswith("_"):
                    continue  # 已经是新格式
                has_old = True
                device_id = cfg.get("deviceId", key)
                sensor_id = cfg.get("sensorId", cfg.get("id", 1))
                new_key = _make_sensor_key(device_id, sensor_id)
                cfg["deviceId"] = device_id
                cfg["sensorId"] = sensor_id
                cfg.setdefault("displayName", cfg.get("name", key))
                cfg.setdefault("sensorType", "light")
                cfg.setdefault("deviceName", "")
                cfg.setdefault("dataTopic", "")
                migrated[new_key] = cfg
            if has_old:
                sensors.clear()
                sensors.update(migrated)
                self.save()
                print(f"[Config] 已迁移 {len(migrated)} 个传感器到新键格式")
            return has_old

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
        with self._lock:
            mqtt_cfg = self._config.setdefault("mqtt", {})
            mqtt_cfg.update(updates)
            return self.save()

    # ------------------------------------------------------------------
    # 模拟参数配置
    # ------------------------------------------------------------------

    def get_simulation_config(self) -> Dict[str, Any]:
        """获取模拟参数（经纬度、云量、温度等）。"""
        with self._lock:
            return dict(self._config.get("simulation", {}))

    def update_simulation_config(self, updates: Dict[str, Any]) -> bool:
        """更新模拟参数。"""
        with self._lock:
            sim = self._config.setdefault("simulation", {})
            sim.update(updates)
            return self.save()

    # ------------------------------------------------------------------
    # 设备身份配置（模拟器自身标识）
    # ------------------------------------------------------------------

    def get_device_config(self) -> Dict[str, Any]:
        with self._lock:
            return dict(self._config.get("device", {}))

    def update_device_config(self, updates: Dict[str, Any]) -> bool:
        with self._lock:
            device_cfg = self._config.setdefault("device", {})
            device_cfg.update(updates)
            return self.save()

    # ------------------------------------------------------------------
    # 后端 URL 配置
    # ------------------------------------------------------------------

    def get_backend_url(self) -> str:
        with self._lock:
            return self._config.get("backendUrl", "http://localhost:8080/api")

    def set_backend_url(self, url: str) -> bool:
        with self._lock:
            self._config["backendUrl"] = url
            return self.save()

    # ------------------------------------------------------------------
    # 传感器配置（新版复合键: {deviceId}_{sensorId}）
    # ------------------------------------------------------------------

    def get_all_sensors(self) -> Dict[str, Any]:
        with self._lock:
            return dict(self._config.get("sensors", {}))

    def get_sensor(self, sensor_key: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            return self._config.get("sensors", {}).get(sensor_key)

    def add_sensor(self, device_id: str, sensor_cfg: Dict[str, Any]) -> Optional[str]:
        """添加传感器，返回新键；已存在则返回 None。"""
        with self._lock:
            sensors = self._config.setdefault("sensors", {})
            sensor_id = sensor_cfg.get("sensorId", sensor_cfg.get("id", int(time.time() * 1000) % 100000))
            key = _make_sensor_key(device_id, sensor_id)

            if key in sensors:
                return None

            default_sensor = {
                "deviceId": device_id,
                "sensorId": sensor_id,
                "displayName": sensor_cfg.get("displayName", ""),
                "sensorType": sensor_cfg.get("sensorType", "light"),
                "deviceName": sensor_cfg.get("deviceName", ""),
                "name": sensor_cfg.get("name", device_id),
                "location": sensor_cfg.get("location", ""),
                "dataTopic": sensor_cfg.get("dataTopic", ""),
                "enabled": True,
                "interval": sensor_cfg.get("interval", sensor_cfg.get("reportFrequency", 5)),
                "dataRange": {"min": 0, "max": 800},
                "lightStatus": "off",
                "controlMode": "auto",
            }
            default_sensor.update(sensor_cfg)
            sensors[key] = default_sensor
            self.save()
            return key

    def update_sensor(self, sensor_key: str, updates: Dict[str, Any]) -> bool:
        """更新指定传感器的字段。"""
        with self._lock:
            sensors = self._config.get("sensors", {})
            if sensor_key not in sensors:
                return False
            sensors[sensor_key].update(updates)
            return self.save()

    def remove_sensor(self, sensor_key: str) -> bool:
        """删除一个传感器。"""
        with self._lock:
            sensors = self._config.get("sensors", {})
            if sensor_key not in sensors:
                return False
            del sensors[sensor_key]
            return self.save()

    def find_sensors_by_device(self, device_id: str) -> Dict[str, Any]:
        """查找某设备下的所有传感器。"""
        with self._lock:
            return {
                k: v for k, v in self._config.get("sensors", {}).items()
                if v.get("deviceId") == device_id
            }

    # ------------------------------------------------------------------
    # 辅助
    # ------------------------------------------------------------------

    def get_broker_url(self) -> str:
        mqtt = self.get_mqtt_config()
        return f"{mqtt['broker']}:{mqtt['port']}"

    def reload(self) -> None:
        with self._lock:
            self._config = self._load()
