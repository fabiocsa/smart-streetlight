#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
智慧路灯系统 — MQTT → 后端 → 数据库 全链路演示脚本

用法:
  1. 确保后端已启动（StreetlightApplication.java）
  2. pip install paho-mqtt requests
  3. python demo_mqtt_to_db.py

演示流程:
  ├── 步骤1: 连接 EMQX (10.18.39.100:1883)
  ├── 步骤2: 发送 3 条模拟传感器数据到 sensor/data
  ├── 步骤3: 通过 REST API 查询数据是否已入库
  └── 步骤4: 打印验证结果 ✅/❌
"""

import json
import time
import sys

try:
    import paho.mqtt.client as mqtt
except ImportError:
    print("❌ 请先安装 paho-mqtt: pip install paho-mqtt")
    sys.exit(1)

try:
    import requests
except ImportError:
    print("❌ 请先安装 requests: pip install requests")
    sys.exit(1)

# ============================================================
# 配置
# ============================================================
EMQX_HOST = "10.18.39.100"
EMQX_PORT = 1883
MQTT_USER = "jieshou"
MQTT_PASS = "jieshou123"
TOPIC = "sensor/data"

BACKEND_URL = "http://localhost:8080"

# ============================================================
# MQTT 发送
# ============================================================
def on_connect(client, userdata, flags, rc, reason=None):
    if rc == 0:
        print(f"  ✅ 已连接到 EMQX ({EMQX_HOST}:{EMQX_PORT})")
    else:
        print(f"  ❌ 连接 EMQX 失败，返回码: {rc}")
        sys.exit(1)

def send_mqtt_messages():
    """发送模拟传感器数据到 EMQX"""

    print("\n" + "=" * 60)
    print("📡 步骤1: 发送 MQTT 消息到 EMQX")
    print("=" * 60)

    client = mqtt.Client(mqtt.CallbackAPIVersion.V2)
    client.on_connect = on_connect
    client.username_pw_set(MQTT_USER, MQTT_PASS)

    client.connect(EMQX_HOST, EMQX_PORT, 10)
    client.loop_start()
    time.sleep(0.5)

    # ---- 消息1: 传感器数据 (SL-001) ----
    msg1 = {
        "type": "sensor",
        "deviceId": "SL-001",
        "lightIntensity": 35.2,
        "reportedAt": time.strftime("%Y-%m-%dT%H:%M:%S")
    }
    client.publish(TOPIC, json.dumps(msg1), qos=1)
    print(f"  📤 发送 → {TOPIC}")
    print(f"     ├─ deviceId: SL-001")
    print(f"     ├─ lightIntensity: 35.2 lux")
    print(f"     └─ type: sensor (传感器数据)")
    time.sleep(0.3)

    # ---- 消息2: 设备心跳 (SL-002) ----
    msg2 = {
        "type": "status",
        "deviceId": "SL-002",
        "status": "online",
        "battery": 85
    }
    client.publish(TOPIC, json.dumps(msg2), qos=1)
    print(f"\n  📤 发送 → {TOPIC}")
    print(f"     ├─ deviceId: SL-002")
    print(f"     ├─ status: online")
    print(f"     └─ type: status (设备心跳)")
    time.sleep(0.3)

    # ---- 消息3: 传感器数据 (SL-003) ----
    msg3 = {
        "type": "sensor",
        "deviceId": "SL-003",
        "lightIntensity": 200.8,
        "reportedAt": time.strftime("%Y-%m-%dT%H:%M:%S")
    }
    client.publish(TOPIC, json.dumps(msg3), qos=1)
    print(f"\n  📤 发送 → {TOPIC}")
    print(f"     ├─ deviceId: SL-003")
    print(f"     ├─ lightIntensity: 200.8 lux")
    print(f"     └─ type: sensor (传感器数据)")

    client.loop_stop()
    client.disconnect()
    print(f"\n  ✅ 3 条消息已全部发送到 EMQX！\n")

# ============================================================
# REST API 验证
# ============================================================
def verify_data():
    """通过 REST API 验证数据是否存入数据库"""

    print("\n" + "=" * 60)
    print("📊 步骤2: 通过 REST API 验证数据入库")
    print("=" * 60)

    all_ok = True

    # ---- 验证1: 查看所有设备 ----
    try:
        resp = requests.get(f"{BACKEND_URL}/api/devices", timeout=5)
        if resp.status_code == 200:
            devices = resp.json()
            print(f"\n  ✅ GET /api/devices → 200 OK")
            print(f"     共 {len(devices)} 台设备:")
            for d in devices:
                status_icon = "🟢" if d["status"] == "online" else "🔴"
                light_icon = "💡" if d["lightStatus"] == "on" else "🌙"
                print(f"     {status_icon} {d['deviceId']} ({d['name']}) "
                      f"→ 状态: {d['status']} | 灯光: {light_icon} {d['lightStatus']} | 模式: {d['controlMode']}")
        else:
            print(f"\n  ❌ GET /api/devices → {resp.status_code}")
            all_ok = False
    except requests.exceptions.ConnectionError:
        print(f"\n  ❌ 无法连接后端! 请确认后端已启动 (http://localhost:8080)")
        return False

    # ---- 验证2: 查看 SL-001 最新传感器数据 ----
    try:
        resp = requests.get(f"{BACKEND_URL}/api/devices/SL-001/sensor-data/latest", timeout=5)
        if resp.status_code == 200:
            data = resp.json()
            print(f"\n  ✅ GET /api/devices/SL-001/sensor-data/latest → 200 OK")
            print(f"     ├─ 光照强度: {data['lightIntensity']} lux")
            print(f"     ├─ 上报时间: {data['reportedAt']}")
            print(f"     └─ 入库时间: {data['createdAt']}")
            print(f"     \n     🎯 验证: MQTT → 后端 → 数据库 链路成功！")
        else:
            print(f"\n  ❌ GET /api/devices/SL-001/sensor-data/latest → {resp.status_code}")
            all_ok = False
    except Exception as e:
        print(f"\n  ❌ 查询传感器数据失败: {e}")
        all_ok = False

    # ---- 验证3: 查看 SL-003 最新传感器数据 ----
    try:
        resp = requests.get(f"{BACKEND_URL}/api/devices/SL-003/sensor-data/latest", timeout=5)
        if resp.status_code == 200:
            data = resp.json()
            print(f"\n  ✅ GET /api/devices/SL-003/sensor-data/latest → 200 OK")
            print(f"     ├─ 光照强度: {data['lightIntensity']} lux")
            print(f"     └─ 上报时间: {data['reportedAt']}")
        else:
            print(f"\n  ⚠️  SL-003 无传感器数据 (可能未注册)")
    except Exception as e:
        print(f"\n  ⚠️  查询 SL-003 传感器数据失败: {e}")

    return all_ok


# ============================================================
# 主流程
# ============================================================
def main():
    print("")
    print("╔══════════════════════════════════════════════════╗")
    print("║    智慧路灯系统 — MQTT → 后端 → 数据库 演示     ║")
    print("║    Smart Streetlight Data Pipeline Demo          ║")
    print("╚══════════════════════════════════════════════════╝")

    print(f"\n📋 前提条件:")
    print(f"   🔹 后端已启动 → http://localhost:8080")
    print(f"   🔹 EMQX 已启动 → {EMQX_HOST}:{EMQX_PORT}")
    print(f"   🔹 MySQL 数据库已初始化 (执行 docs/init.sql)")

    # 等待用户确认
    input(f"\n⏎ 按回车键开始演示...")

    # 步骤1: 发送 MQTT 消息
    send_mqtt_messages()

    # 等待后端处理
    print("  ⏳ 等待后端处理消息 (1秒)...")
    time.sleep(1)

    # 步骤2: API 验证
    success = verify_data()

    # 总结
    print("\n" + "=" * 60)
    print("📋 演示总结")
    print("=" * 60)
    if success:
        print("""
  ✅ 全链路验证通过！

  数据流向:
     Python脚本 ──MQTT──→ EMQX ──MQTT──→ Spring Boot后端 ──JPA──→ MySQL数据库
         ↓                    ↓              ↓                    ↓
      发送消息             消息代理        解析入库            持久化存储
                                          ↑
                                    WebSocket推送
                                          ↓
                                    前端实时展示

  查看方式:
     📍 REST API:   curl http://localhost:8080/api/devices
     📍 数据库:      SELECT * FROM sensor_data WHERE device_id='SL-001';
     📍 后端日志:    查看 IDEA 控制台输出
""")
    else:
        print("""
  ⚠️  部分验证未通过，请检查:
    1. 后端是否已启动 (IDEA 中运行 StreetlightApplication.java)
    2. EMQX 是否可达 (telnet 10.18.39.100 1883)
    3. MySQL 数据库是否已初始化 (执行 docs/init.sql)
    4. 后端日志中是否有报错信息
""")


if __name__ == "__main__":
    main()
