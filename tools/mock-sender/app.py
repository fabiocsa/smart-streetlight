#!/usr/bin/env python3
"""
智慧路灯 Mock 模拟数据发送器 - Flask Web 应用
=============================================
提供 Web UI 管理传感器、配置 MQTT 连接、查看实时日志。
"""

import json
import logging
import queue
import threading
import time
from datetime import datetime
from typing import Any, Dict

from flask import Flask, jsonify, render_template, request, Response, stream_with_context

from sender.config_manager import ConfigManager
from sender.mqtt_client import MqttClientManager
from sender.sensor_manager import SensorManager

# ---------------------------------------------------------------------------
# 日志系统 — 同时输出到控制台和内存队列（供 Web UI 实时显示）
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

    # 控制台输出
    console = logging.StreamHandler()
    console.setLevel(logging.INFO)
    console.setFormatter(logging.Formatter(
        "[%(asctime)s] [%(name)s] %(levelname)s: %(message)s",
        datefmt="%H:%M:%S",
    ))
    root.addHandler(console)

    # 队列输出（Web UI）
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
    """获取整体运行状态。"""
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


# ================================ API: 传感器 ================================

@app.route("/api/sensors")
def api_sensors():
    """获取传感器列表。"""
    return jsonify(sensor_mgr.list_sensors())


@app.route("/api/sensors/<device_id>")
def api_sensor(device_id: str):
    """获取单个传感器详情。"""
    s = sensor_mgr.get_sensor(device_id)
    if not s:
        return jsonify({"error": "传感器不存在"}), 404
    return jsonify(s)


@app.route("/api/sensors", methods=["POST"])
def api_add_sensor():
    """添加传感器。"""
    data = request.get_json(force=True)
    device_id = data.get("deviceId", "").strip()
    if not device_id:
        return jsonify({"error": "deviceId 不能为空"}), 400

    ok = sensor_mgr.add_sensor(device_id, data)
    if not ok:
        return jsonify({"error": f"传感器 {device_id} 已存在"}), 409
    return jsonify({"message": f"传感器 {device_id} 已添加"}), 201


@app.route("/api/sensors/<device_id>", methods=["DELETE"])
def api_remove_sensor(device_id: str):
    """删除传感器。"""
    ok = sensor_mgr.remove_sensor(device_id)
    if not ok:
        return jsonify({"error": "传感器不存在"}), 404
    return jsonify({"message": f"传感器 {device_id} 已删除"})


@app.route("/api/sensors/<device_id>/start", methods=["POST"])
def api_start_sensor(device_id: str):
    """启动传感器。"""
    ok = sensor_mgr.start_sensor(device_id)
    if not ok:
        return jsonify({"error": "传感器不存在或已运行"}), 400
    return jsonify({"message": f"传感器 {device_id} 已启动"})


@app.route("/api/sensors/<device_id>/stop", methods=["POST"])
def api_stop_sensor(device_id: str):
    """停止传感器。"""
    ok = sensor_mgr.stop_sensor(device_id)
    if not ok:
        return jsonify({"error": "传感器不存在"}), 400
    return jsonify({"message": f"传感器 {device_id} 已停止"})


@app.route("/api/sensors/<device_id>/config", methods=["PUT"])
def api_update_sensor_config(device_id: str):
    """更新传感器配置。"""
    data = request.get_json(force=True)
    ok = sensor_mgr.update_sensor_config(device_id, data)
    if not ok:
        return jsonify({"error": "传感器不存在"}), 404
    return jsonify({"message": "配置已更新"})


# ================================ API: MQTT 配置 ================================

@app.route("/api/config/mqtt")
def api_get_mqtt_config():
    """获取 MQTT 连接配置。"""
    cfg = config_mgr.get_mqtt_config()
    # 隐藏密码
    cfg = dict(cfg)
    cfg["password"] = "******" if cfg.get("password") else ""
    return jsonify(cfg)


@app.route("/api/config/mqtt", methods=["PUT"])
def api_update_mqtt_config():
    """更新 MQTT 连接配置并重新连接。"""
    data = request.get_json(force=True)
    current = config_mgr.get_mqtt_config()

    # 保留原有密码（如果前端传了掩码）
    if data.get("password") in ("******", ""):
        data["password"] = current.get("password", "")

    ok = config_mgr.update_mqtt_config(data)
    if not ok:
        return jsonify({"error": "保存配置失败"}), 500

    # 重新连接
    new_cfg = config_mgr.get_mqtt_config()
    mqtt_mgr.connect(new_cfg)
    # 重连后重新加载传感器
    sensor_mgr.stop_all()
    sensor_mgr.load_from_config()

    return jsonify({"message": "MQTT 配置已更新并重新连接"})


@app.route("/api/config/mqtt/reconnect", methods=["POST"])
def api_reconnect_mqtt():
    """手动重连 MQTT。"""
    cfg = config_mgr.get_mqtt_config()
    mqtt_mgr.connect(cfg)
    sensor_mgr.load_from_config()
    return jsonify({"message": "MQTT 重连成功" if mqtt_mgr.is_connected else "MQTT 正在后台重连..."})


# ================================ API: 日志流 (SSE) ================================

@app.route("/api/logs")
def api_get_logs():
    """获取最近的日志。"""
    logs = []
    while not log_queue.empty():
        try:
            logs.append(log_queue.get_nowait())
        except queue.Empty:
            break
    return jsonify(logs)


@app.route("/api/logs/stream")
def api_log_stream():
    """SSE 实时日志流。"""
    def generate():
        # 先发送一个初始连接消息
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


# ================================ API: 配置指令 (模拟后端下发) ================================

@app.route("/api/mock-config/send", methods=["POST"])
def api_send_mock_config():
    """模拟后端下发配置指令到 Mock Sender。"""
    data = request.get_json(force=True)
    action = data.get("action", "")
    device_id = data.get("deviceId", "")
    params = data.get("params", {})

    if not action:
        return jsonify({"error": "action 不能为空"}), 400

    # 处理配置指令
    ok = _handle_mock_config_command(data)
    if ok:
        return jsonify({"message": f"指令 '{action}' 已执行", "action": action, "deviceId": device_id})
    else:
        return jsonify({"warning": f"指令 '{action}' 部分执行", "action": action}), 200


def _handle_mock_config_command(payload: Dict[str, Any]) -> bool:
    """处理 Mock Sender 配置指令（来自 MQTT 或 HTTP API）。"""
    action = payload.get("action", "")
    device_id = payload.get("deviceId", "")
    params = payload.get("params", {})

    logger.info(f"处理配置指令: action={action}, deviceId={device_id}, params={params}")

    if action == "set_frequency":
        interval = params.get("interval", 5)
        if device_id:
            return sensor_mgr.update_sensor_config(device_id, {"interval": interval})
        else:
            # 全局修改所有传感器
            for s in sensor_mgr.list_sensors():
                sensor_mgr.update_sensor_config(s["deviceId"], {"interval": interval})
            return True

    elif action == "set_data_range":
        data_range = {"min": params.get("min", 0), "max": params.get("max", 800)}
        if device_id:
            return sensor_mgr.update_sensor_config(device_id, {"dataRange": data_range})
        return True

    elif action == "set_light_status":
        if device_id:
            sensor_mgr.handle_control_command(device_id, {
                "command": params.get("status", "off"),
                "source": "auto",
            })
            return True
        return False

    elif action == "add_sensor":
        if device_id:
            return sensor_mgr.add_sensor(device_id, params)
        return False

    elif action == "remove_sensor":
        if device_id:
            return sensor_mgr.remove_sensor(device_id)
        return False

    elif action == "stop_sensor":
        if device_id:
            return sensor_mgr.stop_sensor(device_id)
        return False

    elif action == "start_sensor":
        if device_id:
            return sensor_mgr.start_sensor(device_id)
        return False

    else:
        logger.warning(f"未知配置指令: {action}")
        return False


# ================================ 启动 ================================

_start_time = 0.0


def main():
    global _start_time, config_mgr, mqtt_mgr, sensor_mgr

    _start_time = time.time()

    # 初始化组件
    config_mgr = ConfigManager()
    mqtt_mgr = MqttClientManager()
    sensor_mgr = SensorManager(config_mgr, mqtt_mgr)

    # 注册 MQTT 回调
    mqtt_mgr.on_control_command = sensor_mgr.handle_control_command
    mqtt_mgr.on_mock_config = _handle_mock_config_command

    # 连接 MQTT
    mqtt_cfg = config_mgr.get_mqtt_config()
    mqtt_mgr.connect(mqtt_cfg)

    # 加载传感器
    sensor_mgr.load_from_config()

    # 获取端口（默认 5050，避免与后端 8080/前端 5173 冲突）
    port = 5050
    logger.info("=" * 55)
    logger.info("  智慧路灯 Mock 模拟数据发送器")
    logger.info(f"  Web UI: http://localhost:{port}")
    logger.info(f"  MQTT:   {mqtt_cfg['broker']}:{mqtt_cfg['port']}")
    logger.info("=" * 55)
    logger.info("  按 Ctrl+C 停止")
    logger.info("")

    try:
        app.run(host="0.0.0.0", port=port, debug=False, use_reloader=False)
    except KeyboardInterrupt:
        pass
    finally:
        logger.info("正在关闭...")
        sensor_mgr.stop_all()
        mqtt_mgr.disconnect()
        logger.info("已安全退出")


if __name__ == "__main__":
    main()
