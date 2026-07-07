"""
智慧路灯 MQTT 数据模拟器
模拟 8 个路灯设备向 MQTT Broker 发送传感器数据
"""

import json
import random
import time
import threading
import signal
import sys
import math
from datetime import datetime, timezone

try:
    import paho.mqtt.client as mqtt
except ImportError:
    print("请先安装 paho-mqtt: pip install paho-mqtt")
    sys.exit(1)

# ======================== 配置 ========================
MQTT_BROKER = "8.130.102.89"
MQTT_PORT = 1883
MQTT_USERNAME = "jieshou"
MQTT_PASSWORD = "jieshou123"
MQTT_CLIENT_ID = "python-simulator-v1"

# 设备列表（含位置信息）
DEVICES = [
    {"deviceId": "SL-001", "name": "路灯A-01", "location": "校门口", "lightStatus": "off"},
    {"deviceId": "SL-002", "name": "路灯A-02", "location": "图书馆前", "lightStatus": "on"},
    {"deviceId": "SL-003", "name": "路灯A-03", "location": "操场东侧", "lightStatus": "off"},
    {"deviceId": "SL-004", "name": "路灯B-01", "location": "行政楼南", "lightStatus": "off"},
    {"deviceId": "SL-005", "name": "路灯B-02", "location": "食堂门口", "lightStatus": "on"},
    {"deviceId": "SL-006", "name": "路灯B-03", "location": "宿舍区1栋", "lightStatus": "off"},
    {"deviceId": "SL-007", "name": "路灯C-01", "location": "教学楼A", "lightStatus": "off"},
    {"deviceId": "SL-008", "name": "路灯C-02", "location": "教学楼B", "lightStatus": "off"},
]

# 各设备的光照基准值 (模拟不同位置的光照差异)
BASE_ILLUMINANCE = {
    "SL-001": 0, "SL-002": 0, "SL-003": 0,
    "SL-004": 0, "SL-005": 0, "SL-006": 0,
    "SL-007": 0, "SL-008": 0,
}

# 模拟运行状态
running = True
device_states = {d["deviceId"]: d.copy() for d in DEVICES}

# ======================== MQTT 回调 ========================
def on_connect(client, userdata, flags, rc):
    if rc == 0:
        print(f"[✓] 已连接到 MQTT Broker ({MQTT_BROKER}:{MQTT_PORT})")
        # 订阅每个设备的控制指令主题
        for device in DEVICES:
            topic = f"streetlight/{device['deviceId']}/control"
            client.subscribe(topic, qos=1)
            print(f"   订阅: {topic}")
    else:
        print(f"[✗] 连接失败, 返回码={rc}")


def on_message(client, userdata, msg):
    """处理接收到的控制指令"""
    try:
        payload = json.loads(msg.payload.decode())
        topic = msg.topic
        print(f"\n[←] 收到控制指令 - topic: {topic}, payload: {payload}")

        # 从 topic 中提取 deviceId: streetlight/{deviceId}/control
        parts = topic.split("/")
        if len(parts) >= 3:
            device_id = parts[1]
            command = payload.get("command")
            source = payload.get("source", "manual")

            # 更新设备灯光状态
            if command in ("on", "off"):
                device_states[device_id]["lightStatus"] = command
                print(f"    → {device_id} 灯光已{command}")

            # 回复执行结果到 streetlight/{deviceId}/control/response
            response = {
                "command": command,
                "result": "success",
                "deviceId": device_id,
                "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
            }
            response_topic = f"streetlight/{device_id}/control/response"
            client.publish(response_topic, json.dumps(response), qos=1)
            print(f"[→] 已回复控制结果到 {response_topic}: {response}")

    except Exception as e:
        print(f"[!] 处理控制指令失败: {e}")


# ======================== 数据生成 ========================
def calculate_illuminance(device_id, elapsed_seconds):
    """
    模拟一天的光照变化:
    - 使用正弦波模拟从日出到日落的光照变化
    - 加入随机波动让数据更真实
    """
    # 模拟24小时光照周期 (86400秒一个完整周期)
    cycle = 86400
    phase = (elapsed_seconds % cycle) / cycle * 2 * math.pi

    # 基础光照: 正弦波, 峰值在中午 (phase=π), 夜间接近0
    base = max(0, math.sin(phase)) * 800

    # 加上随机波动 (±50)
    noise = random.uniform(-50, 50)

    # 不同设备略有差异 (偏移 ±10%)
    device_offset = hash(device_id) % 20 - 10
    variation = 1.0 + device_offset / 100.0

    illuminance = max(0, (base + noise) * variation)
    return round(illuminance, 1)


def publish_sensor_data(client):
    """定时发布传感器数据（含丰富字段）"""
    start_time = time.time()
    interval = 5  # 每5秒发一次

    while running:
        elapsed = time.time() - start_time

        for device in DEVICES:
            device_id = device["deviceId"]
            illuminance = calculate_illuminance(device_id, elapsed)

            # 根据光照值决定灯光状态
            light_on = device_states[device_id]["lightStatus"] == "on"
            light_status = "ON" if light_on else "OFF"

            # 模拟电压和功率（灯亮时功耗高）
            voltage = round(random.uniform(215, 235), 2)
            power = round(random.uniform(60, 80), 2) if light_on else 0

            payload = {
                "deviceId": device_id,
                "location": device["location"],
                "illuminance": illuminance,
                "voltage": voltage,
                "power": power,
                "status": light_status,
                "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
            }
            # 使用设备独立主题: streetlight/{deviceId}/sensor/data
            sensor_topic = f"streetlight/{device_id}/sensor/data"
            client.publish(sensor_topic, json.dumps(payload), qos=1)

        time.sleep(interval)


def publish_heartbeat(client):
    """定时发布设备心跳"""
    interval = 10  # 每10秒发一次

    while running:
        for device in DEVICES:
            device_id = device["deviceId"]
            payload = {
                "deviceId": device_id,
                "status": "online",
                "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
            }
            client.publish(f"streetlight/{device_id}/status", json.dumps(payload), qos=1)
        time.sleep(interval)


# ======================== 信号处理 ========================
def signal_handler(sig, frame):
    global running
    print("\n\n正在停止模拟器...")
    running = False
    client.disconnect()
    sys.exit(0)


# ======================== 主程序 ========================
if __name__ == "__main__":
    print("=" * 50)
    print("  智慧路灯 MQTT 数据模拟器")
    print(f"  Broker: {MQTT_BROKER}:{MQTT_PORT}")
    print(f"  设备数: {len(DEVICES)}")
    print("=" * 50)

    # 创建 MQTT 客户端
    client = mqtt.Client(client_id=MQTT_CLIENT_ID, protocol=mqtt.MQTTv311)
    client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)
    client.on_connect = on_connect
    client.on_message = on_message

    # 连接 Broker
    try:
        client.connect(MQTT_BROKER, MQTT_PORT, 60)
    except Exception as e:
        print(f"[✗] 连接 MQTT Broker 失败: {e}")
        print(f"    请确认 EMQX 已启动: http://{MQTT_BROKER}:18083")
        sys.exit(1)

    # 启动后台线程
    client.loop_start()
    sensor_thread = threading.Thread(target=publish_sensor_data, args=(client,), daemon=True)
    heartbeat_thread = threading.Thread(target=publish_heartbeat, args=(client,), daemon=True)
    sensor_thread.start()
    heartbeat_thread.start()

    # 注册信号处理
    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    print("\n开始发送模拟数据 (按 Ctrl+C 停止)...\n")

    # 主循环: 显示统计信息
    try:
        while running:
            time.sleep(30)
            print(f"[{datetime.now().strftime('%H:%M:%S')}] 模拟器运行中... 设备: {len(DEVICES)} 个")
    except KeyboardInterrupt:
        signal_handler(None, None)
