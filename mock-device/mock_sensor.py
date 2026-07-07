#!/usr/bin/env python3
import paho.mqtt.client as mqtt
import json
import time
import random
import datetime

MQTT_BROKER = "127.0.0.1"
MQTT_PORT = 1883
MQTT_TOPIC = "sensor/data"
MQTT_USERNAME = "fasong"      # ← 已启用
MQTT_PASSWORD = "fasong123"   # ← 已启用

DEVICES = [
    {"deviceId": "LIGHT_001", "location": "A区-路灯1"},
    {"deviceId": "LIGHT_002", "location": "A区-路灯2"},
    {"deviceId": "LIGHT_003", "location": "B区-路灯3"}
]
SEND_INTERVAL = 5


def generate_data(device):
    now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    return {
        "deviceId": device["deviceId"],
        "location": device["location"],
        "timestamp": now,
        "illuminance": round(random.uniform(50, 950), 1),
        "voltage": round(random.uniform(210, 230), 2),
        "status": random.choice(["ON", "ON", "ON", "OFF"]),
        "power": round(random.uniform(30, 80), 2)
    }


def on_connect(client, userdata, flags, rc):
    if rc == 0:
        print("✅ 已连接到 EMQX Broker")
    else:
        print(f"❌ 连接失败，返回码: {rc}")


def main():
    client = mqtt.Client()
    client.on_connect = on_connect
    client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)  # ← 已启用

    try:
        client.connect(MQTT_BROKER, MQTT_PORT, 60)
        client.loop_start()
    except Exception as e:
        print(f"❌ 无法连接到 EMQX: {e}")
        return

    print("📤 开始发送 Mock 数据（按 Ctrl+C 停止）...")
    try:
        while True:
            for device in DEVICES:
                payload = generate_data(device)
                client.publish(MQTT_TOPIC, json.dumps(payload))
                print(f"📨 [{device['deviceId']}] {payload}")
            time.sleep(SEND_INTERVAL)
    except KeyboardInterrupt:
        print("\n🛑 已停止")


if __name__ == "__main__":
    main()