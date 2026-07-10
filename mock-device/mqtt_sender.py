"""
智慧路灯 MQTT 数据发送器 (Web版)
提供可视化界面，可编辑JSON数据并手动发送到MQTT
"""

import json
import os
import threading
import time
from datetime import datetime

import flask
from flask import Flask, request, jsonify, render_template_string

try:
    import paho.mqtt.client as mqtt
except ImportError:
    print("请先安装 paho-mqtt: pip install paho-mqtt")
    exit(1)

# ======================== 配置 ========================
MQTT_BROKER = "8.130.102.89"
MQTT_PORT = 1883
MQTT_USERNAME = "jieshou"
MQTT_PASSWORD = "jieshou123"
MQTT_CLIENT_ID = "python-sender-web-v1"
APP_PORT = 5001

# 设备列表
DEVICES = [
    {"deviceId": "SL-001", "name": "路灯A-01", "location": "校门口"},
    {"deviceId": "SL-002", "name": "路灯A-02", "location": "图书馆前"},
    {"deviceId": "SL-003", "name": "路灯A-03", "location": "操场东侧"},
    {"deviceId": "SL-004", "name": "路灯B-01", "location": "行政楼南"},
    {"deviceId": "SL-005", "name": "路灯B-02", "location": "食堂门口"},
    {"deviceId": "SL-006", "name": "路灯B-03", "location": "宿舍区1栋"},
    {"deviceId": "SL-007", "name": "路灯C-01", "location": "教学楼A"},
    {"deviceId": "SL-008", "name": "路灯C-02", "location": "教学楼B"},
]

# ======================== MQTT客户端 ========================
mqtt_client = None
mqtt_connected = False
mqtt_lock = threading.Lock()

# 设备连接状态 (独立于MQTT客户端)
device_status = {}  # deviceId -> {"connected": bool}

# 发送历史
send_history = []
MAX_HISTORY = 100

# 接收到的消息
received_messages = []
MAX_RECEIVED = 100


def get_now_str():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def on_connect(client, userdata, flags, rc, properties=None):
    global mqtt_connected
    if rc == 0:
        mqtt_connected = True
        print(f"[{get_now_str()}] [OK] MQTT connected ({MQTT_BROKER}:{MQTT_PORT})")
        # 连接成功后订阅topic
        client.subscribe("sensor/data", qos=1)
        client.subscribe("control/response", qos=1)
        client.subscribe("streetlight/+/status", qos=1)
        print(f"[{get_now_str()}] 已订阅: sensor/data, control/response, streetlight/+/status")
    else:
        mqtt_connected = False
        print(f"[{get_now_str()}] [FAIL] MQTT connection failed, rc={rc}")


def on_disconnect(client, userdata, rc, properties=None):
    global mqtt_connected
    mqtt_connected = False
    print(f"[{get_now_str()}] MQTT disconnected")


def on_message(client, userdata, msg):
    """收到MQTT消息时存储"""
    try:
        payload = msg.payload.decode()
        record = {
            "time": get_now_str(),
            "topic": msg.topic,
            "payload": payload,
            "qos": msg.qos
        }
        received_messages.insert(0, record)
        if len(received_messages) > MAX_RECEIVED:
            received_messages.pop()
    except Exception as e:
        print(f"处理接收消息失败: {e}")


def init_mqtt():
    global mqtt_client, mqtt_connected
    with mqtt_lock:
        if mqtt_client is not None:
            try:
                mqtt_client.disconnect()
            except:
                pass
        mqtt_client = mqtt.Client(client_id=MQTT_CLIENT_ID, protocol=mqtt.MQTTv311, callback_api_version=mqtt.CallbackAPIVersion.VERSION2)
        mqtt_client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)
        mqtt_client.on_connect = on_connect
        mqtt_client.on_disconnect = on_disconnect
        mqtt_client.on_message = on_message
        try:
            mqtt_client.connect(MQTT_BROKER, MQTT_PORT, 60)
            mqtt_client.loop_start()
            # 等待连接完成
            time.sleep(0.5)
            if mqtt_connected:
                # 订阅所有需要监听的topic
                mqtt_client.subscribe("sensor/data", qos=1)
                mqtt_client.subscribe("control/response", qos=1)
                mqtt_client.subscribe("streetlight/+/status", qos=1)
                print(f"[{get_now_str()}] 已订阅: sensor/data, control/response, streetlight/+/status")
            return mqtt_connected
        except Exception as e:
            print(f"[{get_now_str()}] [FAIL] MQTT connect error: {e}")
            return False


def disconnect_mqtt():
    global mqtt_client, mqtt_connected
    with mqtt_lock:
        if mqtt_client is not None:
            try:
                mqtt_client.disconnect()
                mqtt_client.loop_stop()
            except:
                pass
        mqtt_client = None
        mqtt_connected = False


def publish_message(topic, payload_str):
    global mqtt_client, mqtt_connected
    with mqtt_lock:
        if mqtt_client is None or not mqtt_connected:
            return False, "MQTT未连接"
        try:
            result = mqtt_client.publish(topic, payload_str, qos=1)
            if result.rc == mqtt.MQTT_ERR_SUCCESS:
                return True, "发送成功"
            else:
                return False, f"发送失败, 返回码={result.rc}"
        except Exception as e:
            return False, f"发送异常: {e}"


# ======================== 消息模板 ========================
def get_sensor_template(device_id):
    return json.dumps({
        "deviceId": device_id,
        "illuminance": 125.5,
        "voltage": 220.0,
        "power": 75.0,
        "status": "OFF",
        "timestamp": get_now_str()
    }, ensure_ascii=False, indent=2)


def get_heartbeat_template(device_id):
    return json.dumps({
        "deviceId": device_id,
        "status": "online",
        "timestamp": datetime.now().strftime("%Y-%m-%dT%H:%M:%SZ")
    }, ensure_ascii=False, indent=2)


def get_control_response_template(device_id):
    return json.dumps({
        "command": "on",
        "result": "success",
        "deviceId": device_id,
        "timestamp": datetime.now().strftime("%Y-%m-%dT%H:%M:%SZ")
    }, ensure_ascii=False, indent=2)


# ======================== Flask Web ========================
app = Flask(__name__)


@app.route("/")
def index():
    return render_template_string(HTML_TEMPLATE,
                                   broker=MQTT_BROKER,
                                   port=MQTT_PORT,
                                   devices=DEVICES)


@app.route("/api/status")
def api_status():
    return jsonify({
        "connected": mqtt_connected,
        "broker": MQTT_BROKER,
        "port": MQTT_PORT
    })


@app.route("/api/devices")
def api_devices():
    result = []
    for d in DEVICES:
        status = device_status.get(d["deviceId"], {"connected": False})
        result.append({**d, "connected": status.get("connected", False)})
    return jsonify(result)


@app.route("/api/devices/add", methods=["POST"])
def api_add_device():
    data = request.get_json()
    if not data or not data.get("deviceId"):
        return jsonify({"success": False, "message": "设备ID不能为空"})

    device_id = data["deviceId"].strip()
    # 检查重复
    for d in DEVICES:
        if d["deviceId"] == device_id:
            return jsonify({"success": False, "message": f"设备 {device_id} 已存在"})

    new_device = {
        "deviceId": device_id,
        "name": data.get("name", "").strip() or device_id,
        "location": data.get("location", "").strip() or "未设置"
    }
    DEVICES.append(new_device)
    device_status[device_id] = {"connected": False}
    return jsonify({"success": True, "device": new_device})


@app.route("/api/devices/<device_id>", methods=["DELETE"])
def api_delete_device(device_id):
    for i, d in enumerate(DEVICES):
        if d["deviceId"] == device_id:
            DEVICES.pop(i)
            device_status.pop(device_id, None)
            return jsonify({"success": True})
    return jsonify({"success": False, "message": f"设备 {device_id} 不存在"})


@app.route("/api/devices/<device_id>/connect", methods=["POST"])
def api_connect_device(device_id):
    found = any(d["deviceId"] == device_id for d in DEVICES)
    if not found:
        return jsonify({"success": False, "connected": False, "message": f"设备 {device_id} 不存在"})

    # 发送 online 心跳
    topic = f"streetlight/{device_id}/status"
    payload = json.dumps({
        "deviceId": device_id,
        "status": "online",
        "timestamp": datetime.now().strftime("%Y-%m-%dT%H:%M:%SZ")
    })
    ok, msg = publish_message(topic, payload)
    if ok:
        device_status[device_id] = {"connected": True}

    return jsonify({"success": ok, "connected": ok, "message": msg})


@app.route("/api/devices/<device_id>/disconnect", methods=["POST"])
def api_disconnect_device(device_id):
    found = any(d["deviceId"] == device_id for d in DEVICES)
    if not found:
        return jsonify({"success": False, "connected": False, "message": f"设备 {device_id} 不存在"})

    # 发送 offline 心跳
    topic = f"streetlight/{device_id}/status"
    payload = json.dumps({
        "deviceId": device_id,
        "status": "offline",
        "timestamp": datetime.now().strftime("%Y-%m-%dT%H:%M:%SZ")
    })
    ok, msg = publish_message(topic, payload)
    if ok:
        device_status[device_id] = {"connected": False}

    return jsonify({"success": ok, "connected": False, "message": msg})


@app.route("/api/connect", methods=["POST"])
def api_connect():
    ok = init_mqtt()
    return jsonify({"success": ok, "connected": mqtt_connected})


@app.route("/api/disconnect", methods=["POST"])
def api_disconnect():
    disconnect_mqtt()
    return jsonify({"success": True, "connected": False})


@app.route("/api/send", methods=["POST"])
def api_send():
    data = request.get_json()
    if not data:
        return jsonify({"success": False, "message": "请求体为空"})

    msg_type = data.get("type", "sensor")
    device_id = data.get("deviceId", "SL-001")
    payload_str = data.get("payload", "")

    # 确定topic
    topic_map = {
        "sensor": "sensor/data",
        "heartbeat": f"streetlight/{device_id}/status",
        "control_response": "control/response",
    }
    topic = topic_map.get(msg_type, "sensor/data")

    # 发送
    ok, msg = publish_message(topic, payload_str)

    # 记录历史
    record = {
        "time": get_now_str(),
        "type": msg_type,
        "deviceId": device_id,
        "topic": topic,
        "payload": payload_str,
        "status": "✓" if ok else "✗",
        "message": msg
    }
    send_history.insert(0, record)
    if len(send_history) > MAX_HISTORY:
        send_history.pop()

    return jsonify({"success": ok, "message": msg, "record": record})


@app.route("/api/history")
def api_history():
    return jsonify(send_history)


@app.route("/api/received")
def api_received():
    return jsonify(received_messages)


# ======================== HTML模板 ========================
HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>智慧路灯 - MQTT数据发送器</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; background: #f0f2f5; color: #333; min-height: 100vh; }
        .header { background: linear-gradient(135deg, #1a73e8, #0d47a1); color: white; padding: 20px 30px; display: flex; align-items: center; justify-content: space-between; }
        .header h1 { font-size: 22px; font-weight: 600; }
        .header .subtitle { font-size: 13px; opacity: 0.85; margin-top: 4px; }
        .status-badge { display: inline-flex; align-items: center; gap: 6px; padding: 6px 14px; border-radius: 20px; font-size: 13px; font-weight: 500; }
        .status-badge.connected { background: rgba(255,255,255,0.2); }
        .status-badge.disconnected { background: rgba(255,255,255,0.15); }
        .dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
        .dot.green { background: #4caf50; box-shadow: 0 0 6px #4caf50; }
        .dot.red { background: #f44336; box-shadow: 0 0 6px #f44336; }

        .container { max-width: 1100px; margin: 0 auto; padding: 20px; display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
        @media (max-width: 768px) { .container { grid-template-columns: 1fr; } }

        .card { background: white; border-radius: 12px; box-shadow: 0 2px 8px rgba(0,0,0,0.08); overflow: hidden; }
        .card-header { padding: 14px 20px; font-size: 15px; font-weight: 600; border-bottom: 1px solid #eee; display: flex; align-items: center; gap: 8px; }
        .card-header .icon { font-size: 18px; }
        .card-body { padding: 20px; }

        .form-group { margin-bottom: 16px; }
        .form-group label { display: block; font-size: 13px; font-weight: 500; color: #555; margin-bottom: 6px; }
        .form-group select, .form-group textarea, .form-group button { width: 100%; }
        .form-group select { padding: 8px 12px; border: 1px solid #ddd; border-radius: 8px; font-size: 14px; background: white; cursor: pointer; }
        .form-group select:focus { border-color: #1a73e8; outline: none; }
        .form-group textarea { padding: 12px; border: 1px solid #ddd; border-radius: 8px; font-size: 13px; font-family: "Consolas", "Monaco", "Courier New", monospace; resize: vertical; min-height: 180px; line-height: 1.5; }
        .form-group textarea:focus { border-color: #1a73e8; outline: none; box-shadow: 0 0 0 3px rgba(26,115,232,0.1); }
        .form-group textarea.error { border-color: #f44336; box-shadow: 0 0 0 3px rgba(244,67,54,0.1); }

        .btn { padding: 10px 20px; border: none; border-radius: 8px; font-size: 14px; font-weight: 500; cursor: pointer; transition: all 0.2s; display: inline-flex; align-items: center; gap: 6px; }
        .btn:active { transform: scale(0.97); }
        .btn-primary { background: #1a73e8; color: white; }
        .btn-primary:hover { background: #1557b0; }
        .btn-primary:disabled { background: #93b8f0; cursor: not-allowed; transform: none; }
        .btn-success { background: #4caf50; color: white; }
        .btn-success:hover { background: #388e3c; }
        .btn-danger { background: #f44336; color: white; }
        .btn-danger:hover { background: #c62828; }
        .btn-outline { background: transparent; border: 1px solid #ddd; color: #666; }
        .btn-outline:hover { background: #f5f5f5; }
        .btn-block { width: 100%; justify-content: center; padding: 12px; font-size: 15px; }

        .row2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }

        .history-table { width: 100%; font-size: 12px; border-collapse: collapse; }
        .history-table th { text-align: left; padding: 8px 10px; background: #fafafa; border-bottom: 2px solid #eee; font-weight: 600; color: #555; white-space: nowrap; }
        .history-table td { padding: 8px 10px; border-bottom: 1px solid #f0f0f0; vertical-align: top; }
        .history-table tr:hover td { background: #fafafa; }
        .history-table .payload-cell { font-family: "Consolas", monospace; font-size: 11px; max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; cursor: pointer; }
        .history-table .payload-cell:hover { white-space: normal; word-break: break-all; }
        .status-ok { color: #4caf50; font-weight: bold; }
        .status-fail { color: #f44336; font-weight: bold; }
        .tab-btn { padding: 8px 16px; border: none; background: none; cursor: pointer; font-size: 13px; color: #888; border-bottom: 2px solid transparent; transition: all 0.2s; }
        .tab-btn.active { color: #1a73e8; border-bottom-color: #1a73e8; font-weight: 600; }
        .tab-btn:hover { color: #333; }
        .tab-content { display: none; }
        .tab-content.active { display: block; }
        .badge-sensor { background: #e3f2fd; color: #1565c0; }
        .badge-heartbeat { background: #f3e5f5; color: #7b1fa2; }
        .badge-control { background: #fff3e0; color: #e65100; }

        .empty-state { text-align: center; padding: 40px 20px; color: #999; }
        .empty-state .icon { font-size: 40px; margin-bottom: 10px; }
        .toast { position: fixed; top: 20px; right: 20px; padding: 12px 20px; border-radius: 8px; color: white; font-size: 14px; z-index: 999; animation: slideIn 0.3s ease; }
        .toast.success { background: #4caf50; }
        .toast.error { background: #f44336; }
        @keyframes slideIn { from { transform: translateX(100%); opacity: 0; } to { transform: translateX(0); opacity: 1; } }
        .conn-bar { display: flex; gap: 8px; margin-bottom: 16px; }
        .conn-info { flex: 1; font-size: 12px; color: #888; padding: 8px 12px; background: #fafafa; border-radius: 8px; }
        .topic-hint { font-size: 12px; color: #888; margin-top: 4px; padding: 4px 8px; background: #f5f5f5; border-radius: 4px; display: inline-block; }

        /* 弹窗 (Modal) */
        .modal-overlay { display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.45); z-index: 1000; align-items: center; justify-content: center; }
        .modal-overlay.active { display: flex; }
        .modal { background: white; border-radius: 14px; width: 720px; max-width: 94vw; max-height: 85vh; display: flex; flex-direction: column; box-shadow: 0 20px 60px rgba(0,0,0,0.3); animation: modalIn 0.25s ease; }
        @keyframes modalIn { from { transform: translateY(20px); opacity: 0; } to { transform: translateY(0); opacity: 1; } }
        .modal-header { padding: 18px 24px; border-bottom: 1px solid #eee; display: flex; align-items: center; justify-content: space-between; font-size: 16px; font-weight: 600; }
        .modal-close { background: none; border: none; font-size: 22px; color: #999; cursor: pointer; padding: 0 4px; line-height: 1; }
        .modal-close:hover { color: #333; }
        .modal-body { padding: 20px 24px; overflow-y: auto; flex: 1; }

        .device-mgr-form { display: flex; gap: 10px; margin-bottom: 18px; flex-wrap: wrap; }
        .device-mgr-form input { flex: 1; min-width: 100px; padding: 8px 12px; border: 1px solid #ddd; border-radius: 8px; font-size: 13px; }
        .device-mgr-form input:focus { border-color: #1a73e8; outline: none; }
        .device-mgr-table { width: 100%; font-size: 13px; border-collapse: collapse; }
        .device-mgr-table th { text-align: left; padding: 10px 12px; background: #fafafa; border-bottom: 2px solid #eee; font-weight: 600; color: #555; white-space: nowrap; }
        .device-mgr-table td { padding: 10px 12px; border-bottom: 1px solid #f0f0f0; vertical-align: middle; }
        .device-mgr-table tr:hover td { background: #f8f9ff; }
        .dev-status-online { color: #4caf50; font-weight: 500; }
        .dev-status-offline { color: #999; }
        .dev-actions { display: flex; gap: 4px; flex-wrap: nowrap; }
        .dev-actions .btn { padding: 4px 10px; font-size: 12px; white-space: nowrap; }
        .btn-sm { padding: 4px 10px; font-size: 12px; }
        .btn-warning { background: #ff9800; color: white; }
        .btn-warning:hover { background: #e68900; }
        .empty-devices { text-align: center; padding: 30px; color: #999; font-size: 14px; }
    </style>
</head>
<body>

<div class="header">
    <div>
        <h1>📡 MQTT 数据发送器</h1>
        <div class="subtitle">智慧路灯系统 · 手动编辑并发送MQTT消息</div>
    </div>
    <div>
        <span class="status-badge" id="statusBadge">
            <span class="dot" id="statusDot"></span>
            <span id="statusText">检查中...</span>
        </span>
        <button class="btn btn-outline" style="margin-left:10px;color:white;border-color:rgba(255,255,255,0.4);" onclick="openDeviceManager()">⚙️ 设备管理</button>
    </div>
</div>

<div class="container">
    <!-- 左栏: 编辑区 -->
    <div class="card">
        <div class="card-header"><span class="icon">✏️</span> 消息编辑</div>
        <div class="card-body">
            <!-- 连接栏 -->
            <div class="conn-bar">
                <div class="conn-info" id="connInfo">Broker: {{ broker }}:{{ port }}</div>
                <button class="btn btn-success" id="connectBtn" onclick="connect()">连接</button>
                <button class="btn btn-outline" id="disconnectBtn" onclick="disconnect()" style="display:none">断开</button>
            </div>

            <div class="row2">
                <div class="form-group">
                    <label>📌 消息类型</label>
                    <select id="msgType" onchange="onTypeChange()">
                        <option value="sensor">🌤️ 传感器数据</option>
                        <option value="heartbeat">💓 心跳</option>
                        <option value="control_response">🔁 控制响应</option>
                    </select>
                </div>
                <div class="form-group">
                    <label>🔧 设备</label>
                    <select id="deviceId" onchange="onDeviceChange()">
                        {% for d in devices %}
                        <option value="{{ d.deviceId }}">{{ d.deviceId }} - {{ d.name }}</option>
                        {% endfor %}
                    </select>
                </div>
            </div>

            <div class="form-group">
                <label>📝 JSON 数据 <span class="topic-hint" id="topicHint">→ sensor/data</span></label>
                <textarea id="payloadEditor" spellcheck="false"></textarea>
            </div>

            <button class="btn btn-primary btn-block" id="sendBtn" onclick="sendMessage()" disabled>
                📤 发送到 MQTT
            </button>
        </div>
    </div>

    <!-- 右栏: 消息监控 -->
    <div class="card">
        <div class="card-header" style="padding-bottom:0;">
            <div style="display:flex;align-items:center;justify-content:space-between;width:100%;">
                <span><span class="icon">📋</span> 消息监控</span>
                <span style="font-size:12px;color:#999;font-weight:400;" id="monitorCount"></span>
            </div>
            <div style="margin-top:8px;">
                <button class="tab-btn active" id="tabSent" onclick="switchTab('sent')">📤 发送</button>
                <button class="tab-btn" id="tabReceived" onclick="switchTab('received')">📥 接收</button>
            </div>
        </div>
        <div class="card-body" style="padding:0;">
            <div id="historyArea" style="max-height:500px;overflow-y:auto;">
                <!-- 发送记录 -->
                <div class="tab-content active" id="tabSentContent">
                    <div class="empty-state" id="emptyHistory">
                        <div class="icon">📭</div>
                        <div>暂无发送记录</div>
                        <div style="font-size:12px;margin-top:4px;">编辑左侧消息后点击发送</div>
                    </div>
                    <table class="history-table" id="historyTable" style="display:none;">
                        <thead>
                            <tr>
                                <th>时间</th>
                                <th>类型</th>
                                <th>Topic</th>
                                <th>状态</th>
                            </tr>
                        </thead>
                        <tbody id="historyBody"></tbody>
                    </table>
                </div>
                <!-- 接收记录 -->
                <div class="tab-content" id="tabReceivedContent">
                    <div class="empty-state" id="emptyReceived">
                        <div class="icon">📡</div>
                        <div>暂无接收消息</div>
                        <div style="font-size:12px;margin-top:4px;">等待MQTT消息中...</div>
                    </div>
                    <table class="history-table" id="receivedTable" style="display:none;">
                        <thead>
                            <tr>
                                <th>时间</th>
                                <th>Topic</th>
                                <th>Payload</th>
                            </tr>
                        </thead>
                        <tbody id="receivedBody"></tbody>
                    </table>
                </div>
            </div>
        </div>
    </div>
</div>

<!-- ======================== 设备管理弹窗 ======================== -->
<div class="modal-overlay" id="deviceModal">
    <div class="modal">
        <div class="modal-header">
            <span>⚙️ 设备管理</span>
            <button class="modal-close" onclick="closeDeviceManager()">&times;</button>
        </div>
        <div class="modal-body">
            <!-- 新增设备表单 -->
            <div class="device-mgr-form">
                <input type="text" id="newDeviceId" placeholder="设备ID (如 SL-009)" style="flex:1.5;">
                <input type="text" id="newDeviceName" placeholder="设备名称 (如 路灯D-01)">
                <input type="text" id="newDeviceLocation" placeholder="位置 (如 体育馆)">
                <button class="btn btn-primary btn-sm" onclick="addDevice()">➕ 新增</button>
            </div>
            <!-- 设备列表 -->
            <div id="deviceListContainer">
                <div class="empty-devices">加载中...</div>
            </div>
        </div>
    </div>
</div>

<script>
// ======================== 状态 ========================
let currentType = 'sensor';
let currentDevice = 'SL-001';

// ======================== 消息模板 ========================
const TEMPLATES = {
    sensor: (dev) => JSON.stringify({
        deviceId: dev,
        illuminance: 125.5,
        voltage: 220.0,
        power: 75.0,
        status: "OFF",
        timestamp: new Date().toLocaleString('zh-CN', { hour12: false })
    }, null, 2),
    heartbeat: (dev) => JSON.stringify({
        deviceId: dev,
        status: "online",
        timestamp: new Date().toISOString().replace('Z', 'Z')
    }, null, 2),
    control_response: (dev) => JSON.stringify({
        command: "on",
        result: "success",
        deviceId: dev,
        timestamp: new Date().toISOString().replace('Z', 'Z')
    }, null, 2)
};

const TOPIC_MAP = {
    sensor: 'sensor/data',
    heartbeat: (dev) => `streetlight/${dev}/status`,
    control_response: 'control/response'
};

// ======================== 初始化 ========================
async function init() {
    await checkStatus();
    onTypeChange();
}

async function checkStatus() {
    try {
        const r = await fetch('/api/status');
        const data = await r.json();
        updateStatus(data.connected);
    } catch(e) {
        updateStatus(false);
    }
}

function updateStatus(connected) {
    const dot = document.getElementById('statusDot');
    const text = document.getElementById('statusText');
    const badge = document.getElementById('statusBadge');
    const sendBtn = document.getElementById('sendBtn');
    const connectBtn = document.getElementById('connectBtn');
    const disconnectBtn = document.getElementById('disconnectBtn');

    if (connected) {
        dot.className = 'dot green';
        text.textContent = '已连接';
        badge.className = 'status-badge connected';
        sendBtn.disabled = false;
        connectBtn.style.display = 'none';
        disconnectBtn.style.display = '';
    } else {
        dot.className = 'dot red';
        text.textContent = '未连接';
        badge.className = 'status-badge disconnected';
        sendBtn.disabled = true;
        connectBtn.style.display = '';
        disconnectBtn.style.display = 'none';
    }
}

async function connect() {
    try {
        const r = await fetch('/api/connect', { method: 'POST' });
        const data = await r.json();
        updateStatus(data.connected);
        if (data.connected) showToast('MQTT已连接', 'success');
        else showToast('MQTT连接失败', 'error');
    } catch(e) {
        showToast('连接请求失败', 'error');
    }
}

async function disconnect() {
    try {
        await fetch('/api/disconnect', { method: 'POST' });
        updateStatus(false);
        showToast('已断开MQTT', 'success');
    } catch(e) {
        showToast('断开请求失败', 'error');
    }
}

// ======================== 编辑器 ========================
function onTypeChange() {
    currentType = document.getElementById('msgType').value;
    const topicHint = document.getElementById('topicHint');
    const topic = typeof TOPIC_MAP[currentType] === 'function'
        ? TOPIC_MAP[currentType](currentDevice)
        : TOPIC_MAP[currentType];
    topicHint.textContent = `→ ${topic}`;
    updatePayload();
}

function onDeviceChange() {
    currentDevice = document.getElementById('deviceId').value;
    onTypeChange();
}

function updatePayload() {
    const fn = TEMPLATES[currentType];
    document.getElementById('payloadEditor').value = fn(currentDevice);
}

function getCurrentTopic() {
    const t = TOPIC_MAP[currentType];
    return typeof t === 'function' ? t(currentDevice) : t;
}

// ======================== 发送 ========================
async function sendMessage() {
    const payload = document.getElementById('payloadEditor').value.trim();
    if (!payload) {
        showToast('请输入JSON数据', 'error');
        return;
    }
    // 验证JSON
    try {
        JSON.parse(payload);
    } catch(e) {
        showToast('JSON格式错误: ' + e.message, 'error');
        document.getElementById('payloadEditor').classList.add('error');
        return;
    }
    document.getElementById('payloadEditor').classList.remove('error');

    const sendBtn = document.getElementById('sendBtn');
    sendBtn.disabled = true;
    sendBtn.textContent = '⏳ 发送中...';

    try {
        const r = await fetch('/api/send', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                type: currentType,
                deviceId: currentDevice,
                payload: payload
            })
        });
        const data = await r.json();
        if (data.success) {
            showToast('✓ ' + data.message, 'success');
        } else {
            showToast('✗ ' + data.message, 'error');
        }
        refreshHistory();
    } catch(e) {
        showToast('发送请求失败: ' + e.message, 'error');
    } finally {
        sendBtn.disabled = false;
        sendBtn.textContent = '📤 发送到 MQTT';
    }
}

// ======================== 历史 ========================
async function refreshHistory() {
    try {
        const r = await fetch('/api/history');
        const records = await r.json();
        renderHistory(records);
    } catch(e) {}
}

function renderHistory(records) {
    const empty = document.getElementById('emptyHistory');
    const table = document.getElementById('historyTable');
    const body = document.getElementById('historyBody');
    const count = document.getElementById('historyCount');

    count.textContent = `(${records.length}条)`;

    if (records.length === 0) {
        empty.style.display = '';
        table.style.display = 'none';
        return;
    }
    empty.style.display = 'none';
    table.style.display = '';

    body.innerHTML = records.map(r => {
        const typeBadge = {
            'sensor': '<span class="badge badge-sensor">传感器</span>',
            'heartbeat': '<span class="badge badge-heartbeat">心跳</span>',
            'control_response': '<span class="badge badge-control">控制响应</span>'
        }[r.type] || r.type;

        const statusHtml = r.status === '✓'
            ? '<span class="status-ok">✓</span>'
            : '<span class="status-fail">✗ ' + r.message + '</span>';

        return `<tr>
            <td style="white-space:nowrap;font-size:11px;color:#888;">${r.time}</td>
            <td>${typeBadge}</td>
            <td style="font-size:11px;color:#666;max-width:200px;word-break:break-all;">${r.topic}</td>
            <td>${statusHtml}</td>
        </tr>`;
    }).join('');
}

function showToast(msg, type) {
    const toast = document.createElement('div');
    toast.className = 'toast ' + type;
    toast.textContent = msg;
    document.body.appendChild(toast);
    setTimeout(() => toast.remove(), 2500);
}

// ======================== Tab切换 ========================
function switchTab(name) {
    document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
    document.getElementById('tab' + name.charAt(0).toUpperCase() + name.slice(1)).classList.add('active');
    document.getElementById('tab' + name.charAt(0).toUpperCase() + name.slice(1) + 'Content').classList.add('active');
}

// ======================== 设备管理 ========================
function openDeviceManager() {
    document.getElementById('deviceModal').classList.add('active');
    loadDevices();
}

function closeDeviceManager() {
    document.getElementById('deviceModal').classList.remove('active');
}

// 点击蒙层关闭弹窗
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('modal-overlay')) {
        closeDeviceManager();
    }
});

async function loadDevices() {
    const container = document.getElementById('deviceListContainer');
    try {
        const r = await fetch('/api/devices');
        const devices = await r.json();
        renderDeviceList(devices);
    } catch(e) {
        container.innerHTML = '<div class="empty-devices">加载失败</div>';
    }
}

function renderDeviceList(devices) {
    const container = document.getElementById('deviceListContainer');
    if (!devices || devices.length === 0) {
        container.innerHTML = '<div class="empty-devices">暂无设备，请在上方添加</div>';
        return;
    }

    let html = `<table class="device-mgr-table">
        <thead><tr>
            <th>设备ID</th>
            <th>名称</th>
            <th>位置</th>
            <th>状态</th>
            <th>操作</th>
        </tr></thead><tbody>`;

    devices.forEach(d => {
        const isOnline = d.connected;
        const statusHtml = isOnline
            ? '<span class="dev-status-online">● 已连接</span>'
            : '<span class="dev-status-offline">○ 未连接</span>';

        const actionHtml = isOnline
            ? `<button class="btn btn-warning btn-sm" onclick="disconnectDevice('${d.deviceId}')">断开</button>`
            : `<button class="btn btn-success btn-sm" onclick="connectDevice('${d.deviceId}')">连接</button>`;

        html += `<tr>
            <td><strong>${d.deviceId}</strong></td>
            <td>${d.name}</td>
            <td style="color:#888;">${d.location}</td>
            <td>${statusHtml}</td>
            <td class="dev-actions">
                ${actionHtml}
                <button class="btn btn-danger btn-sm" onclick="deleteDevice('${d.deviceId}')">删除</button>
            </td>
        </tr>`;
    });

    html += '</tbody></table>';
    container.innerHTML = html;
}

async function addDevice() {
    const deviceId = document.getElementById('newDeviceId').value.trim();
    const name = document.getElementById('newDeviceName').value.trim();
    const location = document.getElementById('newDeviceLocation').value.trim();

    if (!deviceId) {
        showToast('请输入设备ID', 'error');
        return;
    }

    try {
        const r = await fetch('/api/devices/add', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ deviceId, name, location })
        });
        const data = await r.json();
        if (data.success) {
            showToast(`设备 ${deviceId} 添加成功`, 'success');
            document.getElementById('newDeviceId').value = '';
            document.getElementById('newDeviceName').value = '';
            document.getElementById('newDeviceLocation').value = '';
            loadDevices();
            updateDeviceDropdown();
        } else {
            showToast(data.message, 'error');
        }
    } catch(e) {
        showToast('添加失败: ' + e.message, 'error');
    }
}

async function deleteDevice(deviceId) {
    if (!confirm(`确定要删除设备 ${deviceId} 吗？`)) return;

    try {
        const r = await fetch(`/api/devices/${deviceId}`, { method: 'DELETE' });
        const data = await r.json();
        if (data.success) {
            showToast(`设备 ${deviceId} 已删除`, 'success');
            loadDevices();
            updateDeviceDropdown();
        } else {
            showToast(data.message, 'error');
        }
    } catch(e) {
        showToast('删除失败: ' + e.message, 'error');
    }
}

async function connectDevice(deviceId) {
    try {
        const r = await fetch(`/api/devices/${deviceId}/connect`, { method: 'POST' });
        const data = await r.json();
        if (data.success) {
            showToast(`设备 ${deviceId} 已连接 (发送online心跳)`, 'success');
        } else {
            showToast(data.message || '连接失败', 'error');
        }
        loadDevices();
    } catch(e) {
        showToast('连接请求失败: ' + e.message, 'error');
    }
}

async function disconnectDevice(deviceId) {
    try {
        const r = await fetch(`/api/devices/${deviceId}/disconnect`, { method: 'POST' });
        const data = await r.json();
        if (data.success) {
            showToast(`设备 ${deviceId} 已断开 (发送offline心跳)`, 'success');
        } else {
            showToast(data.message || '断开失败', 'error');
        }
        loadDevices();
    } catch(e) {
        showToast('断开请求失败: ' + e.message, 'error');
    }
}

async function updateDeviceDropdown() {
    try {
        const r = await fetch('/api/devices');
        const devices = await r.json();
        const sel = document.getElementById('deviceId');
        const currentVal = sel.value;
        sel.innerHTML = devices.map(d =>
            `<option value="${d.deviceId}">${d.deviceId} - ${d.name}</option>`
        ).join('');
        if (devices.some(d => d.deviceId === currentVal)) {
            sel.value = currentVal;
        }
        onDeviceChange();
    } catch(e) {}
}

// ======================== 接收消息 ========================
async function refreshReceived() {
    try {
        const r = await fetch('/api/received');
        const records = await r.json();
        renderReceived(records);
    } catch(e) {}
}

function renderReceived(records) {
    const empty = document.getElementById('emptyReceived');
    const table = document.getElementById('receivedTable');
    const body = document.getElementById('receivedBody');
    const count = document.getElementById('monitorCount');

    count.textContent = `(发送${document.querySelector('#historyBody').childElementCount || 0} | 接收${records.length})`;

    if (records.length === 0) {
        empty.style.display = '';
        table.style.display = 'none';
        return;
    }
    empty.style.display = 'none';
    table.style.display = '';

    body.innerHTML = records.map(r => {
        let payload = r.payload || '';
        // 截断过长payload
        const truncated = payload.length > 120 ? payload.substring(0, 120) + '...' : payload;
        return `<tr>
            <td style="white-space:nowrap;font-size:11px;color:#888;">${r.time}</td>
            <td style="font-size:11px;color:#666;max-width:150px;word-break:break-all;">${r.topic}</td>
            <td class="payload-cell" title="${payload.replace(/"/g, '&quot;')}">${truncated}</td>
        </tr>`;
    }).join('');
}

// ======================== 定时刷新 ========================
// 每2秒检查连接状态和历史
setInterval(checkStatus, 2000);
setInterval(refreshHistory, 2000);
setInterval(refreshReceived, 2000);
setInterval(updateDeviceDropdown, 5000);

// 初始化
init();
</script>
</body>
</html>
"""

# ======================== 启动 ========================
if __name__ == "__main__":
    print("=" * 50)
    print("  智慧路灯 MQTT 数据发送器")
    print(f"  Broker: {MQTT_BROKER}:{MQTT_PORT}")
    print(f"  Web界面: http://localhost:{APP_PORT}")
    print("=" * 50)
    print("")
    print("首次访问时会自动尝试连接MQTT...")
    print("按 Ctrl+C 停止服务")

    # 初始化MQTT连接
    init_mqtt()

    # 抑制Flask开发服务器警告（开发工具不需要此提示）
    import werkzeug.serving
    werkzeug.serving._log_warning = lambda *a, **kw: None

    # 启动Flask
    app.run(host="0.0.0.0", port=APP_PORT, debug=False, threaded=True)
