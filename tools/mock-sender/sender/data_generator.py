"""
数据生成模块
===========
生成模拟传感器数据，包括光照强度、电压、功率等。
使用正弦波模拟 24 小时光照周期，加入随机噪声使数据更真实。
"""

import math
import random
import time
from datetime import datetime, timezone
from typing import Any, Dict, Optional


def generate_sensor_data(
    device_id: str,
    light_status: str,
    elapsed_seconds: float,
    data_range: Optional[Dict[str, float]] = None,
    extra_fields: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """
    生成一条完整的传感器数据 payload。

    参数:
        device_id: 设备唯一标识 (如 SL-001)
        light_status: 当前灯光状态 "on" / "off"
        elapsed_seconds: 从启动开始的运行秒数，用于模拟一天的光照变化
        data_range: 光照范围 {min, max}，默认 0~800
        extra_fields: 额外字段会合并到返回值中

    返回:
        符合 MQTT sensor/data topic 的 dict
    """
    if data_range is None:
        data_range = {"min": 0, "max": 800}

    illuminance = _calc_illuminance(device_id, elapsed_seconds, data_range)
    light_on = light_status.lower() == "on"

    payload = {
        "deviceId": device_id,
        "illuminance": illuminance,
        "lightIntensity": illuminance,
        "voltage": round(random.uniform(215, 235), 2),
        "power": round(random.uniform(60, 80), 2) if light_on else 0,
        "status": "ON" if light_on else "OFF",
        "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }

    if extra_fields:
        payload.update(extra_fields)

    return payload


def _calc_illuminance(device_id: str, elapsed_seconds: float, data_range: Dict[str, float]) -> float:
    """
    计算当前光照强度（Lux）。

    算法:
        - 24 小时正弦周期模拟日照
        - 峰值 = data_range['max']，谷值 ≈ 0
        - 叠加 ±5% 随机噪声
        - 不同设备有微小偏移 (基于 device_id 的哈希)
    """
    cycle = 86400  # 24 小时
    phase = (elapsed_seconds % cycle) / cycle * 2 * math.pi

    max_lux = data_range.get("max", 800)
    # 正弦波: 中午 (phase=π) 达到峰值，夜间接近 0
    base = max(0, math.sin(phase)) * max_lux

    # 随机噪声 (±5%)
    noise = random.uniform(-0.05, 0.05) * max_lux

    # 设备差异 (±8%)
    dev_offset = (hash(device_id) % 17 - 8) / 100.0
    variation = 1.0 + dev_offset

    illuminance = max(0, (base + noise) * variation)
    return round(illuminance, 1)


def generate_heartbeat(device_id: str, status: str = "online") -> Dict[str, Any]:
    """生成心跳数据包。"""
    return {
        "deviceId": device_id,
        "status": status,
        "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


def generate_control_response(
    device_id: str, command: str, result: str = "success"
) -> Dict[str, Any]:
    """生成控制指令响应数据包。"""
    return {
        "command": command,
        "result": result,
        "deviceId": device_id,
        "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
