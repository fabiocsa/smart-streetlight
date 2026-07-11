"""
数据生成模块 (v2 — 真实时钟驱动)
=============================
基于真实时钟的太阳位置模型生成符合物理规律的传感器数据。
支持：光照昼夜变化、季节长度差异、纬度变化、云量扰动、温度联动。

算法参考：
  - 太阳赤纬: Spencer (1971) 傅里叶级数
  - 大气光程: 简化 Beer-Lambert 模型
  - 温度: 季节性 + 日变化正弦模型
"""

import math
import random
import time
from datetime import datetime, timezone, timedelta
from typing import Any, Dict, Optional, Tuple

# ---------------------------------------------------------------------------
# 重庆默认值
# ---------------------------------------------------------------------------
DEFAULT_LAT = 29.5
DEFAULT_LON = 106.5
DEFAULT_TZ_OFFSET = 8  # UTC+8 (Asia/Shanghai)

# ---------------------------------------------------------------------------
# 太阳位置计算
# ---------------------------------------------------------------------------

def _solar_declination(day_of_year: int) -> float:
    """
    太阳赤纬 (Spencer 1971 傅里叶级数)。
    精度 ±0.01°，足够模拟光照变化。

    返回: 弧度
    """
    day_angle = 2.0 * math.pi * (day_of_year - 1) / 365.0
    decl_deg = (
        0.006918
        - 0.399912 * math.cos(day_angle)
        + 0.070257 * math.sin(day_angle)
        - 0.006758 * math.cos(2 * day_angle)
        + 0.000907 * math.sin(2 * day_angle)
        - 0.002697 * math.cos(3 * day_angle)
        + 0.001480 * math.sin(3 * day_angle)
    )
    return math.radians(decl_deg)


def _equation_of_time(day_of_year: int) -> float:
    """
    均时差 (Equation of Time)。
    返回: 分钟
    """
    day_angle = 2.0 * math.pi * (day_of_year - 1) / 365.0
    eot_min = 229.18 * (
        0.000075
        + 0.001868 * math.cos(day_angle)
        - 0.032077 * math.sin(day_angle)
        - 0.014615 * math.cos(2 * day_angle)
        - 0.040849 * math.sin(2 * day_angle)
    )
    return eot_min


def _solar_elevation(
    lat: float, lon: float, dt: datetime, tz_offset: float = 0.0
) -> float:
    """
    计算给定经纬度、时间的太阳高度角。

    参数:
        lat:       纬度 (度)
        lon:       经度 (度)
        dt:        当地时间 (naive datetime)
        tz_offset: 时区偏移 (小时)，如 UTC+8 则传入 8.0

    返回: 太阳高度角 (弧度)，地平线以下为负值

    公式:
        UTC_hour   = local_hour - tz_offset
        solar_hour = UTC_hour + lon/15 + EoT/60
        hour_angle = (solar_hour - 12) × 15°
    """
    lat_rad = math.radians(lat)
    decl_rad = _solar_declination(dt.timetuple().tm_yday)
    eot_min = _equation_of_time(dt.timetuple().tm_yday)

    hour_dec = dt.hour + dt.minute / 60.0 + dt.second / 3600.0

    # 当地时间 → UTC → 太阳时
    utc_hour = hour_dec - tz_offset
    solar_hour = utc_hour + lon / 15.0 + eot_min / 60.0

    # 时角: 以太阳正午(=12h) 为零点，下午为正
    hour_angle = math.radians((solar_hour - 12.0) * 15.0)

    sin_elev = (
        math.sin(lat_rad) * math.sin(decl_rad)
        + math.cos(lat_rad) * math.cos(decl_rad) * math.cos(hour_angle)
    )
    # 裁剪到 [-1, 1] 避免浮点误差导致 arcsin 异常
    sin_elev = max(-1.0, min(1.0, sin_elev))
    return math.asin(sin_elev)


# ---------------------------------------------------------------------------
# 光照计算
# ---------------------------------------------------------------------------

def _elevation_to_illuminance(elev_rad: float, max_lux: float,
                               cloud_opacity: float = 0.0) -> float:
    """
    太阳高度角 → 地面光照强度 (Lux)。

    模型说明:
      - elev < -6° (天文昏影结束): 完全黑暗, 0 Lux
      - -6° ≤ elev < 0° (晨昏蒙影): 指数渐变过渡
      - elev ≥ 0°: 日间, 大气光程修正 + 云量衰减

    参数:
        elev_rad: 太阳高度角 (弧度)
        max_lux:  当地晴空正午理论峰值 (Lux)
        cloud_opacity: 云量不透明度 [0, 1]

    返回: 光照强度 (Lux)
    """
    elev_deg = math.degrees(elev_rad)

    if elev_deg < -6.0:
        # 完全黑暗
        return 0.0

    if elev_deg < 0.0:
        # 晨昏蒙影: 从 0 Lux 指数过渡到 ~max_lux × 0.005
        # elev=-6° → ≈0 Lux, elev=0° → ≈4 Lux (民用晨光)
        twilight_factor = math.exp((elev_deg + 6.0) / 2.5)
        return max_lux * 0.001 * twilight_factor

    # 日间: 大气光程修正
    # 太阳越低，光穿过大气层路径越长，衰减越大
    elev_safe = max(math.radians(1.0), elev_rad)  # 避免除零
    air_mass = 1.0 / math.sin(elev_safe)
    optical_depth = 0.15  # 大气光学厚度 (可调)
    clear_sky = max_lux * math.exp(-optical_depth * (air_mass - 1.0))

    # 云量衰减: 云越厚，光照越低
    cloud_factor = 1.0 - cloud_opacity * 0.75

    # 微噪声 ±2%
    noise = 1.0 + random.uniform(-0.02, 0.02)

    return max(0.0, clear_sky * cloud_factor * noise)


# ---------------------------------------------------------------------------
# 云量模拟
# ---------------------------------------------------------------------------

# 模块级云量状态 (随模拟时间缓慢变化)
_cloud_state: Dict[str, Any] = {
    "opacity": 0.3,          # 当前不透明度 [0, 1]
    "last_update": 0.0,      # 上次更新时间 (monotonic seconds)
    "change_interval": 1800, # 变化间隔 (秒) — 默认 30 分钟
    "max_opacity": 0.8,
}


def _get_cloud_opacity(change_interval: int = 1800,
                        max_opacity: float = 0.8) -> float:
    """
    获取当前云量不透明度，每 change_interval 秒缓慢随机变化。

    返回: [0, max_opacity] 之间的值
    """
    now = time.monotonic()
    state = _cloud_state
    state["change_interval"] = change_interval
    state["max_opacity"] = max_opacity

    if now - state["last_update"] >= change_interval:
        # 随机游走: 在 [-0.3, +0.3] 范围内变化
        delta = random.uniform(-0.3, 0.3)
        new_val = state["opacity"] + delta
        state["opacity"] = max(0.0, min(max_opacity, new_val))
        state["last_update"] = now

    return state["opacity"]


def _reset_cloud_state() -> None:
    """重置云量状态 (用于测试)。"""
    _cloud_state["opacity"] = 0.3
    _cloud_state["last_update"] = 0.0


# ---------------------------------------------------------------------------
# 温度模拟
# ---------------------------------------------------------------------------

def _simulate_temperature(dt: datetime, lat: float) -> float:
    """
    模拟当前温度 (°C)。

    模型:
      T = T_annual_mean + T_seasonal + T_daily + noise

    季节: 夏至 (day 172) 峰值, 冬至 (day 355) 谷值
    日变化: 峰值在下午 2 点 (滞后太阳正午 2 小时)

    参数:
        dt:  当地时间
        lat: 纬度 (用于调整年平均温度)

    返回: 温度 (°C)
    """
    day_of_year = dt.timetuple().tm_yday

    # 纬度修正: 每度纬度约降低 0.8°C 年均温
    lat_offset = (lat - 30.0) * 0.8
    annual_mean = 21.5 - lat_offset       # 默认重庆年均 ~21.5°C
    annual_amplitude = 13.5               # 季节振幅
    daily_amplitude = 5.0                 # 日振幅

    # 季节周期 (峰值在夏至 day 172 附近)
    season_phase = 2.0 * math.pi * (day_of_year - 172) / 365.0
    T_seasonal = annual_amplitude * math.cos(season_phase)

    # 日变化 (峰值在下午 14:00)
    hour_dec = dt.hour + dt.minute / 60.0
    daily_phase = 2.0 * math.pi * (hour_dec - 14.0) / 24.0
    T_daily = daily_amplitude * math.cos(daily_phase)

    # 微噪声
    T_noise = random.uniform(-1.0, 1.0)

    return round(annual_mean + T_seasonal + T_daily + T_noise, 1)


# ---------------------------------------------------------------------------
# 湿度模拟
# ---------------------------------------------------------------------------

def _simulate_humidity(dt: datetime, temperature: float) -> float:
    """
    模拟相对湿度 (%)。

    模型:
      - 夜间湿度高 (露水/辐射冷却), 午后湿度低
      - 与温度负相关: 温度越高, 相对湿度越低
      - 基础范围 40% ~ 95%

    参数:
        dt:          当地时间
        temperature: 当前温度 (°C), 用于负相关计算

    返回: 相对湿度 (%)
    """
    hour_dec = dt.hour + dt.minute / 60.0

    # 日变化: 凌晨 5 点峰值, 下午 14 点谷值
    daily_phase = 2.0 * math.pi * (hour_dec - 5.0) / 24.0
    daily_rh = 15.0 * math.cos(daily_phase)  # ±15% 日变化

    # 温度负相关: 每升高 1°C, 湿度降低 ~1.8%
    temp_correction = (temperature - 20.0) * -1.8

    # 基础湿度 ~75%
    base_rh = 75.0
    rh = base_rh + daily_rh + temp_correction + random.uniform(-3.0, 3.0)

    return round(max(30.0, min(98.0, rh)), 1)


# ---------------------------------------------------------------------------
# 主生成函数
# ---------------------------------------------------------------------------

def generate_sensor_data(
    light_status: str,
    data_range: Optional[Dict[str, float]] = None,
    extra_fields: Optional[Dict[str, Any]] = None,
    brightness: Optional[int] = None,
    sim_config: Optional[Dict[str, Any]] = None,
    sensor_type: str = "light",
    sensor_id: Optional[int] = None,
) -> Dict[str, Any]:
    """
    生成一条完整的传感器数据 payload (真实时钟驱动, v4: 不再携带 deviceId)。

    参数:
        light_status: 当前灯光状态 "on" / "off"
        data_range:   光照范围 {min, max}，默认 0~800
        extra_fields: 额外字段会合并到返回值中
        brightness:   手动亮度百分比 0-100，仅在 light_status=on 时生效
        sim_config:   模拟配置 (经纬度/云量/温度参数)，未传则用默认值
        sensor_type:  传感器类型 (light/temperature/humidity/power)
        sensor_id:    传感器定义 ID

    返回:
        符合 MQTT sensor/data topic 的 dict (不含 deviceId)
    """
    sim = sim_config or {}
    lat = sim.get("latitude", DEFAULT_LAT)
    lon = sim.get("longitude", DEFAULT_LON)
    tz_offset = sim.get("timezoneOffset", DEFAULT_TZ_OFFSET)
    cloud_interval = sim.get("cloudChangeInterval", 1800)
    cloud_max = sim.get("cloudCoverMax", 0.8)

    if data_range is None:
        data_range = {"min": 0, "max": 800}

    now_utc = datetime.now(timezone.utc)
    now_local = now_utc + timedelta(hours=tz_offset)
    light_on = light_status.lower() == "on"
    max_lux = data_range.get("max", 800)

    if light_on and brightness is not None:
        natural = _calc_real_illuminance(
            now_local, lat, lon, max_lux, cloud_interval, cloud_max, tz_offset
        )
        brightness_contrib = (brightness / 100.0) * max_lux
        illuminance = round(brightness_contrib * 0.7 + natural * 0.3 + random.uniform(-5, 5), 1)
        illuminance = max(0.0, min(illuminance, max_lux * 1.1))
    else:
        illuminance = _calc_real_illuminance(
            now_local, lat, lon, max_lux, cloud_interval, cloud_max, tz_offset
        )

    temperature = _simulate_temperature(now_local, lat)
    humidity = _simulate_humidity(now_local, temperature)
    voltage = round(random.uniform(218.0, 234.0), 1)
    if light_on:
        power = round(random.uniform(62.0, 78.0), 2)
    else:
        power = round(random.uniform(0.3, 1.8), 2)
    cloud_opacity = _cloud_state["opacity"]

    # v4: 不再包含 deviceId
    payload = {
        "sensorType": sensor_type,
        "illuminance": illuminance,
        "lightIntensity": illuminance,
        "temperature": temperature,
        "humidity": humidity,
        "voltage": voltage,
        "power": power,
        "cloudCover": round(cloud_opacity, 2),
        "status": "ON" if light_on else "OFF",
        "timestamp": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
    }

    if sensor_id is not None:
        payload["sensorId"] = sensor_id
    if brightness is not None:
        payload["brightness"] = brightness
    if extra_fields:
        payload.update(extra_fields)

    return payload


def _calc_real_illuminance(
    now_local: datetime,
    lat: float,
    lon: float,
    max_lux: float,
    cloud_interval: int,
    cloud_max: float,
    tz_offset: float = 0.0,
) -> float:
    """使用真实时钟计算当前光照强度（Lux）。"""
    elev = _solar_elevation(lat, lon, now_local, tz_offset)
    cloud = _get_cloud_opacity(cloud_interval, cloud_max)
    return round(_elevation_to_illuminance(elev, max_lux, cloud), 1)


# ---------------------------------------------------------------------------
# 保留旧接口兼容 (供外部调试直接调用)
# ---------------------------------------------------------------------------

def _calc_illuminance(device_id: str, elapsed_seconds: float,
                       data_range: Dict[str, float]) -> float:
    """
    [已废弃] 旧版基于 elapsed_seconds 的计算。
    保留仅为向后兼容，新代码应使用 generate_sensor_data() 的真实时钟模式。
    """
    cycle = 86400
    phase = (elapsed_seconds % cycle) / cycle * 2 * math.pi
    max_lux = data_range.get("max", 800)
    base = max(0, math.sin(phase)) * max_lux
    noise = random.uniform(-0.05, 0.05) * max_lux
    dev_offset = (hash(device_id) % 17 - 8) / 100.0
    variation = 1.0 + dev_offset
    return round(max(0, (base + noise) * variation), 1)


# ---------------------------------------------------------------------------
# 消息模板渲染
# ---------------------------------------------------------------------------

def render_message_template(template: str, context: Dict[str, Any]) -> str:
    """
    使用上下文变量渲染消息模板。

    支持的变量 (v4: 不再支持 {{deviceId}}):
      {{lightIntensity}} {{illuminance}} {{temperature}}
      {{voltage}} {{power}} {{cloudCover}} {{status}} {{timestamp}}
      {{sensorType}} {{displayName}} {{sensorId}} {{controlMode}}

    也支持 {{payload}} 来插入整个生成的 payload JSON。
    """
    result = template
    # 简单变量替换
    for key, value in context.items():
        if isinstance(value, (int, float)):
            result = result.replace(f"{{{{{key}}}}}", str(value))
        elif isinstance(value, str):
            result = result.replace(f"{{{{{key}}}}}", value)
        elif isinstance(value, dict):
            import json as _json
            result = result.replace(f"{{{{{key}}}}}", _json.dumps(value, ensure_ascii=False))
    return result


def generate_sensor_data_with_template(
    template: str,
    light_status: str,
    data_range: Optional[Dict[str, float]] = None,
    extra_fields: Optional[Dict[str, Any]] = None,
    brightness: Optional[int] = None,
    sim_config: Optional[Dict[str, Any]] = None,
    sensor_type: str = "light",
    sensor_id: Optional[int] = None,
) -> str:
    """根据自定义模板生成传感器数据消息字符串（v4: 不携带 deviceId）。"""
    payload = generate_sensor_data(
        light_status=light_status,
        data_range=data_range,
        extra_fields=extra_fields,
        brightness=brightness,
        sim_config=sim_config,
        sensor_type=sensor_type,
        sensor_id=sensor_id,
    )

    if not template or not template.strip():
        import json as _json
        return _json.dumps(payload, ensure_ascii=False)

    # 添加额外上下文
    context = dict(payload)
    context["payload"] = payload  # 支持 {{payload}} 引用整个对象

    return render_message_template(template, context)


# ---------------------------------------------------------------------------
# 固定内容模式的示例覆盖字段（仅写想固定的字段即可，其余由算法自动生成）
# ---------------------------------------------------------------------------

# 固定内容模式的完整字段模板（v4: 不含 deviceId）
# 固定模式下算法先算完整 payload，再用下面的字段覆盖对应值，只需写想固定的字段
SAMPLE_TEMPLATES = {
    "light": {
        "illuminance": 40,
        "lightIntensity": 40,
        "temperature": 28.3,
        "voltage": 226,
        "power": 65,
        "cloudCover": 0.3,
        "status": "OFF",
        "sensorType": "light",
        "timestamp": "{{timestamp}}",
    },
    "temperature": {
        "temperature": 35.5,
        "voltage": 226,
        "power": 0.5,
        "cloudCover": 0.3,
        "sensorType": "temperature",
        "timestamp": "{{timestamp}}",
    },
    "humidity": {
        "humidity": 80.0,
        "temperature": 28.3,
        "voltage": 226,
        "power": 0.5,
        "sensorType": "humidity",
        "timestamp": "{{timestamp}}",
    },
    "power": {
        "power": 80.0,
        "voltage": 230.0,
        "temperature": 28.3,
        "sensorType": "power",
        "timestamp": "{{timestamp}}",
    },
}


def generate_sample_content(sensor_type: str) -> str:
    """根据传感器类型生成固定内容的完整字段模板（v4: 不含 deviceId）。"""
    import json as _json
    template = SAMPLE_TEMPLATES.get(sensor_type, SAMPLE_TEMPLATES["light"])
    sample = dict(template)
    sample.pop("_comment", None)  # 兼容旧模板中的 _comment 字段
    return _json.dumps(sample, ensure_ascii=False, indent=2)


def generate_heartbeat(sensor_id: int, status: str = "online") -> Dict[str, Any]:
    """生成心跳数据包（v4: 使用 sensor_id，不携带 deviceId）。"""
    return {
        "sensorId": sensor_id,
        "status": status,
        "temperature": _simulate_temperature(
            datetime.now(timezone.utc) + timedelta(hours=DEFAULT_TZ_OFFSET),
            DEFAULT_LAT,
        ),
        "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


# ---------------------------------------------------------------------------
# 调试入口
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    """打印 24 小时光照曲线供人工检查。"""
    TZ = 8  # UTC+8
    print(f"{'时间':>10} | {'高度角':>7} | {'光照(Lux)':>9} | {'温度(°C)':>8} | {'云量':>4}")
    print("-" * 62)
    for h in range(0, 24):
        dt = datetime(2026, 7, 8, h, 0, 0)
        elev = _solar_elevation(DEFAULT_LAT, DEFAULT_LON, dt, TZ)
        lux = _elevation_to_illuminance(elev, 800, 0.3)
        temp = _simulate_temperature(dt, DEFAULT_LAT)
        print(f"{dt:%m-%d %H:%M} | {math.degrees(elev):+6.1f}° | {lux:9.1f} | {temp:8.1f} | 0.30")
