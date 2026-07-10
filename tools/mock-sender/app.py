#!/usr/bin/env python3
"""
智慧路灯 Mock 模拟数据发送器 - Flask Web 应用
=============================================
纯 MQTT 通信：启动时通过 MQTT 注册设备，后端自动发现。
不再直连数据库，模拟真实 IoT 设备行为。
传感器内部键格式: {deviceId}_{sensorId} (如 SL-001_1)
"""

import json
import logging
import queue
import threading
import time
from datetime import datetime
from typing import Any, Dict

from flask import Flask, jsonify, render_template, request, Response, stream_with_context

from sender.config_manager import ConfigManager, _make_sensor_key
from sender.mqtt_client import MqttClientManager
from sender.sensor_manager import SensorManager, _sensor_label

# ---------------------------------------------------------------------------
# 日志系统
# ---------------------------------------------------------------------------

log_queue: queue.Queue = queue.Queue(maxsize=500)


class QueueHandler(logging.Handler):
    def emit(self, record):
        try:
            msg = self.format(record)
            log_queue.put_nowait({
                "time": datetime.now().strftime("%H:%M:%S"),
                "level": record.levelname,
                "message": msg,
            })
        except queue.Full:
            pass


def setup_logging():
    root = logging.getLogger("mock-sender")
    root.setLevel(logging.DEBUG)
    console = logging.StreamHandler()
    console.setLevel(logging.INFO)
    console.setFormatter(logging.Formatter(
        "[%(asctime)s] [%(name)s] %(levelname)s: %(message)s",
        datefmt="%H:%M:%S",
    ))
    root.addHandler(console)
    qh = QueueHandler()
    qh.setLevel(logging.INFO)
    qh.setFormatter(logging.Formatter("%(message)s"))
    root.addHandler(qh)


# ---------------------------------------------------------------------------
# Flask App
# ---------------------------------------------------------------------------

app = Flask(__name__)
setup_logging()
logger = logging.getLogger("mock-sender.app")

# 全局组件（在 main 中初始化）
config_mgr: ConfigManager = None
mqtt_mgr: MqttClientManager = None
sensor_mgr: SensorManager = None


# ================================ Web 页面 ================================

@app.route("/")
def index():
    return render_template("index.html")


# ================================ API: 状态 ================================

@app.route("/api/status")
def api_status():
    mqtt_cfg = config_mgr.get_mqtt_config()
    sensors = sensor_mgr.list_sensors()
    return jsonify({
        "mqtt": {
            "broker": mqtt_cfg.get("broker", ""),
            "port": mqtt_cfg.get("port", 1883),
            "connected": mqtt_mgr.is_connected,
        },
        "sensors": {
            "total": len(sensors),
            "running": sum(1 for s in sensors if s["running"]),
        },
        "uptime": time.time() - _start_time if _start_time else 0,
    })


# ================================ API: 设备注册 ================================

# ================================ API: 传感器 ================================

@app.route("/api/sensors")
def api_sensors():
    return jsonify(sensor_mgr.list_sensors())


@app.route("/api/sensors/<sensor_key>")
def api_sensor(sensor_key: str):
    s = sensor_mgr.get_sensor(sensor_key)
    if not s:
        return jsonify({"error": "传感器不存在"}), 404
    return jsonify(s)


@app.route("/api/sensors", methods=["POST"])
def api_add_sensor():
    data = request.get_json(force=True)
    sensor_id = data.get("sensorId") or int(time.time() * 1000) % 100000
    data["sensorId"] = sensor_id
    # v4: 不再携带 deviceId，传感器独立注册
    key = sensor_mgr.add_sensor(data)
    if not key:
        return jsonify({"error": "传感器已存在"}), 409
    return jsonify({"message": "传感器已添加", "sensorKey": key, "sensorId": sensor_id}), 201


@app.route("/api/sensors/<sensor_key>", methods=["DELETE"])
def api_remove_sensor(sensor_key: str):
    ok = sensor_mgr.remove_sensor(sensor_key)
    if not ok:
        return jsonify({"error": "传感器不存在"}), 404
    return jsonify({"message": f"传感器 {sensor_key} 已删除"})


@app.route("/api/sensors/<sensor_key>/start", methods=["POST"])
def api_start_sensor(sensor_key: str):
    ok = sensor_mgr.start_sensor(sensor_key)
    if not ok:
        return jsonify({"error": "传感器不存在或已运行"}), 400
    return jsonify({"message": f"传感器 {sensor_key} 已启动"})


@app.route("/api/sensors/<sensor_key>/stop", methods=["POST"])
def api_stop_sensor(sensor_key: str):
    ok = sensor_mgr.stop_sensor(sensor_key)
    if not ok:
        return jsonify({"error": "传感器不存在"}), 400
    return jsonify({"message": f"传感器 {sensor_key} 已停止"})


@app.route("/api/sensors/<sensor_key>/config", methods=["PUT"])
def api_update_sensor_config(sensor_key: str):
    data = request.get_json(force=True)
    ok = sensor_mgr.update_sensor_config(sensor_key, data)
    if not ok:
        return jsonify({"error": "传感器不存在"}), 404
    return jsonify({"message": "配置已更新"})


@app.route("/api/sensors/<sensor_key>/publish-once", methods=["POST"])
def api_publish_once(sensor_key: str):
    ok = sensor_mgr.publish_once(sensor_key)
    if not ok:
        return jsonify({"error": "传感器不存在"}), 404
    return jsonify({"message": f"传感器 {sensor_key} 已发送一次数据"})


@app.route("/api/sensors/stop-all", methods=["POST"])
def api_stop_all_sensors():
    count = sensor_mgr.stop_all_sending()
    return jsonify({"message": f"已停止 {count} 个传感器", "stoppedCount": count})


@app.route("/api/sensors/start-all", methods=["POST"])
def api_start_all_sensors():
    count = sensor_mgr.start_all()
    return jsonify({"message": f"已启动 {count} 个传感器", "startedCount": count})


@app.route("/api/sensors/history", methods=["GET"])
def api_get_history():
    filter_key = request.args.get("key", "")
    history = sensor_mgr.get_history(filter_key)
    return jsonify(history)


@app.route("/api/sensors/history", methods=["DELETE"])
def api_clear_history():
    sensor_mgr.clear_history()
    return jsonify({"message": "发送历史已清空"})


@app.route("/api/sensors/<sensor_key>/message-template", methods=["PUT"])
def api_update_message_template(sensor_key: str):
    data = request.get_json(force=True)
    template = data.get("template", "")
    ok = sensor_mgr.update_sensor_config(sensor_key, {"messageTemplate": template})
    if not ok:
        return jsonify({"error": "传感器不存在"}), 404
    return jsonify({"message": "消息模板已更新"})


@app.route("/api/sensors/<sensor_key>/message-template", methods=["GET"])
def api_get_message_template(sensor_key: str):
    s = sensor_mgr.get_sensor(sensor_key)
    if not s:
        return jsonify({"error": "传感器不存在"}), 404
    worker = sensor_mgr._workers.get(sensor_key)
    if not worker:
        return jsonify({"error": "传感器未运行"}), 404
    return jsonify({
        "template": worker.config.get("messageTemplate", ""),
        "autoSendMode": worker.config.get("autoSendMode", "algorithm"),
        "autoSendContent": worker.config.get("autoSendContent", ""),
    })


@app.route("/api/sensors/<sensor_key>/auto-send-config", methods=["PUT"])
def api_update_auto_send_config(sensor_key: str):
    data = request.get_json(force=True)
    updates = {}
    if "autoSendMode" in data:
        updates["autoSendMode"] = data["autoSendMode"]
    if "autoSendContent" in data:
        updates["autoSendContent"] = data["autoSendContent"]
    if not updates:
        return jsonify({"error": "无有效更新字段"}), 400
    ok = sensor_mgr.update_sensor_config(sensor_key, updates)
    if not ok:
        return jsonify({"error": "传感器不存在"}), 404
    return jsonify({"message": "自动发送配置已更新", "updates": updates})


@app.route("/api/sensors/generate-sample/<sensor_type>", methods=["GET"])
def api_generate_sample(sensor_type: str):
    from sender.data_generator import generate_sample_content
    sample = generate_sample_content(sensor_type)
    return jsonify({"sample": sample})


# ================================ API: MQTT 配置 ================================

@app.route("/api/config/mqtt")
def api_get_mqtt_config():
    cfg = config_mgr.get_mqtt_config()
    cfg = dict(cfg)
    cfg["password"] = "******" if cfg.get("password") else ""
    return jsonify(cfg)


@app.route("/api/config/mqtt", methods=["PUT"])
def api_update_mqtt_config():
    data = request.get_json(force=True)
    current = config_mgr.get_mqtt_config()
    if data.get("password") in ("******", ""):
        data["password"] = current.get("password", "")
    ok = config_mgr.update_mqtt_config(data)
    if not ok:
        return jsonify({"error": "保存配置失败"}), 500
    new_cfg = config_mgr.get_mqtt_config()
    mqtt_mgr.connect(new_cfg)
    sensor_mgr.shutdown()
    sensor_mgr.load_from_config()
    return jsonify({"message": "MQTT 配置已更新并重新连接"})


@app.route("/api/config/mqtt/reconnect", methods=["POST"])
def api_reconnect_mqtt():
    cfg = config_mgr.get_mqtt_config()
    mqtt_mgr.connect(cfg)
    sensor_mgr.load_from_config()
    return jsonify({"message": "MQTT 重连成功" if mqtt_mgr.is_connected else "MQTT 正在后台重连..."})


# ================================ API: 日志流 (SSE) ================================

@app.route("/api/logs")
def api_get_logs():
    logs = []
    while not log_queue.empty():
        try:
            logs.append(log_queue.get_nowait())
        except queue.Empty:
            break
    return jsonify(logs)


@app.route("/api/logs/stream")
def api_log_stream():
    def generate():
        yield f"data: {json.dumps({'type': 'connected'})}\n\n"
        while True:
            try:
                record = log_queue.get(timeout=30)
                yield f"data: {json.dumps(record)}\n\n"
            except queue.Empty:
                yield ": keepalive\n\n"
    return Response(
        stream_with_context(generate()),
        mimetype="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


# ================================ 配置指令处理 ================================

def _handle_mock_config_command(payload: Dict[str, Any]) -> bool:
    """处理本地配置指令（v4: 不再支持 deviceId 范围和 bind/unbind）。"""
    action = payload.get("action", "")
    params = payload.get("params", {})
    logger.info(f"处理配置指令: action={action}")

    if action == "set_frequency":
        interval = params.get("interval", 5)
        for s in sensor_mgr.list_sensors():
            sensor_mgr.update_sensor_config(s["sensorKey"], {"interval": interval})
        return True

    elif action == "set_data_range":
        data_range = {"min": params.get("min", 0), "max": params.get("max", 800)}
        for s in sensor_mgr.list_sensors():
            sensor_mgr.update_sensor_config(s["sensorKey"], {"dataRange": data_range})
        return True

    elif action == "set_light_status":
        # v4: 通过 sensor_id 指定目标传感器
        sensor_id = params.get("sensorId")
        if sensor_id:
            sensor_mgr.on_sensor_cmd(int(sensor_id), {
                "command": params.get("status", "off"),
                "source": "auto",
            })
        return True

    elif action == "add_sensor":
        sensor_id = params.get("sensorId", int(time.time() * 1000) % 100000)
        params["sensorId"] = sensor_id
        key = sensor_mgr.add_sensor(params)
        return key is not None

    elif action == "remove_sensor":
        sensor_id = params.get("sensorId")
        if sensor_id:
            key = _make_sensor_key(sensor_id)
            return sensor_mgr.remove_sensor(key)
        return False

    elif action in ("stop_sensor", "start_sensor"):
        sensor_id = params.get("sensorId")
        if sensor_id:
            key = _make_sensor_key(sensor_id)
            return sensor_mgr.start_sensor(key) if action == "start_sensor" else sensor_mgr.stop_sensor(key)
        return False

    else:
        logger.warning(f"未知配置指令: {action}")
        return False


@app.route("/api/mock-config/send", methods=["POST"])
def api_send_mock_config():
    data = request.get_json(force=True)
    ok = _handle_mock_config_command(data)
    return jsonify({"message": "指令已执行", "success": ok})


# ================================ 启动 ================================

_start_time = 0.0


def main():
    global _start_time, config_mgr, mqtt_mgr, sensor_mgr

    _start_time = time.time()

    # 初始化组件
    config_mgr = ConfigManager()
    mqtt_mgr = MqttClientManager()
    sensor_mgr = SensorManager(config_mgr, mqtt_mgr)

    # 注册 MQTT 回调 (v4: on_sensor_cmd 替代 on_control_command)
    mqtt_mgr.on_sensor_cmd = sensor_mgr.on_sensor_cmd
    mqtt_mgr.on_connected = lambda: sensor_mgr.re_register_all()

    # 连接 MQTT
    mqtt_cfg = config_mgr.get_mqtt_config()
    mqtt_mgr.connect(mqtt_cfg)

    # 从本地配置加载并启动所有传感器
    sensor_mgr.load_from_config()

    # 启动
    port = 5050
    logger.info("=" * 55)
    logger.info("  智慧路灯 Mock 模拟数据发送器 (纯传感器)")
    logger.info(f"  Web UI:  http://localhost:{port}")
    logger.info(f"  MQTT:    {mqtt_cfg['broker']}:{mqtt_cfg['port']}")
    logger.info("=" * 55)
    logger.info("  按 Ctrl+C 停止")
    logger.info("")

    try:
        app.run(host="0.0.0.0", port=port, debug=False, use_reloader=False)
    except KeyboardInterrupt:
        pass
    finally:
        logger.info("正在关闭...")
        sensor_mgr.shutdown()
        mqtt_mgr.disconnect()
        logger.info("已安全退出")


if __name__ == "__main__":
    main()
