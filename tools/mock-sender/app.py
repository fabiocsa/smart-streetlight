#!/usr/bin/env python3
"""
智慧路灯 Mock 模拟数据发送器 - Flask Web 应用
=============================================
提供 Web UI 管理传感器、配置 MQTT 连接、查看实时日志。
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
        "backendUrl": config_mgr.get_backend_url(),
        "uptime": time.time() - _start_time if _start_time else 0,
    })


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
    """手动添加传感器（Web UI 调用）。"""
    data = request.get_json(force=True)
    device_id = data.get("deviceId", "").strip()
    if not device_id:
        return jsonify({"error": "deviceId 不能为空"}), 400

    sensor_id = data.get("sensorId") or int(time.time() * 1000) % 100000
    data["sensorId"] = sensor_id

    key = sensor_mgr.add_sensor(device_id, data)
    if not key:
        return jsonify({"error": f"传感器已存在"}), 409
    return jsonify({"message": "传感器已添加", "sensorKey": key}), 201


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
    """手动触发一次传感器数据发送。"""
    ok = sensor_mgr.publish_once(sensor_key)
    if not ok:
        return jsonify({"error": "传感器不存在"}), 404
    return jsonify({"message": f"传感器 {sensor_key} 已发送一次数据"})


@app.route("/api/sensors/stop-all", methods=["POST"])
def api_stop_all_sensors():
    """停止所有传感器的自动发送（不删除传感器）。"""
    count = sensor_mgr.stop_all_sending()
    return jsonify({"message": f"已停止 {count} 个传感器", "stoppedCount": count})


@app.route("/api/sensors/start-all", methods=["POST"])
def api_start_all_sensors():
    """启动所有已停止的传感器。"""
    count = sensor_mgr.start_all()
    return jsonify({"message": f"已启动 {count} 个传感器", "startedCount": count})


@app.route("/api/sensors/history", methods=["GET"])
def api_get_history():
    """获取发送历史，可按 sensorKey 筛选。"""
    filter_key = request.args.get("key", "")
    history = sensor_mgr.get_history(filter_key)
    return jsonify(history)


@app.route("/api/sensors/history", methods=["DELETE"])
def api_clear_history():
    """清空发送历史。"""
    sensor_mgr.clear_history()
    return jsonify({"message": "发送历史已清空"})


@app.route("/api/sensors/sync-from-backend", methods=["POST"])
def api_sync_from_backend():
    """从后端 REST API 同步传感器列表。"""
    count = sensor_mgr.load_from_backend()
    return jsonify({
        "message": f"已从后端同步 {count} 个传感器",
        "syncedCount": count,
    })


@app.route("/api/sensors/sync-from-db", methods=["POST"])
def api_sync_from_db():
    """从 MySQL 数据库直接同步传感器列表。"""
    count = sensor_mgr.sync_from_database()
    return jsonify({
        "message": f"已从数据库同步 {count} 个传感器",
        "syncedCount": count,
    })


@app.route("/api/sensors/<sensor_key>/unbind", methods=["POST"])
def api_unbind_sensor(sensor_key: str):
    """解绑传感器（DB device_id=NULL），停止 worker 并从列表中移除。"""
    s = sensor_mgr.get_sensor(sensor_key)
    if not s:
        return jsonify({"error": "传感器不存在"}), 404

    sensor_id = s.get("sensorId")
    if not sensor_id:
        return jsonify({"error": "传感器 ID 未知，无法操作数据库"}), 400

    ok = sensor_mgr.unbind_sensor(sensor_key, int(sensor_id))
    if not ok:
        return jsonify({"error": "解绑失败，请检查数据库连接"}), 500

    logger.info(f"[API] 传感器 {sensor_key} 已解绑 (DB device_id=NULL)")
    return jsonify({"message": f"传感器 {sensor_key} 已解绑", "sensorKey": sensor_key})


@app.route("/api/sensors/<sensor_key>/rebind", methods=["POST"])
def api_rebind_sensor(sensor_key: str):
    """将传感器换绑到另一设备。"""
    data = request.get_json(force=True)
    new_device_id = data.get("deviceId", "").strip()
    if not new_device_id:
        return jsonify({"error": "目标 deviceId 不能为空"}), 400

    s = sensor_mgr.get_sensor(sensor_key)
    if not s:
        return jsonify({"error": "传感器不存在"}), 404

    sensor_id = s.get("sensorId")
    if not sensor_id:
        return jsonify({"error": "传感器 ID 未知，无法操作数据库"}), 400

    new_key = sensor_mgr.rebind_sensor(sensor_key, int(sensor_id), new_device_id)
    if not new_key:
        return jsonify({"error": "换绑失败，请检查数据库连接或目标设备是否存在"}), 500

    logger.info(f"[API] 传感器 {sensor_key} 已换绑到设备 {new_device_id} (新key={new_key})")
    return jsonify({
        "message": f"传感器已绑定到设备 {new_device_id}",
        "newKey": new_key,
    })


@app.route("/api/devices/list", methods=["GET"])
def api_list_devices():
    """获取所有设备列表（供换绑选择器使用）。"""
    if db_client:
        devices = db_client.get_all_devices()
        return jsonify([{"deviceId": d["device_id"], "name": d.get("name", ""),
                         "location": d.get("location", "")} for d in devices])
    return jsonify([])


@app.route("/api/sensors/<sensor_key>/message-template", methods=["PUT"])
def api_update_message_template(sensor_key: str):
    """更新传感器的自定义消息模板。"""
    data = request.get_json(force=True)
    template = data.get("template", "")
    ok = sensor_mgr.update_sensor_config(sensor_key, {"messageTemplate": template})
    if not ok:
        return jsonify({"error": "传感器不存在"}), 404
    logger.info(f"传感器 {sensor_key} 消息模板已更新")
    return jsonify({"message": "消息模板已更新"})


@app.route("/api/sensors/<sensor_key>/message-template", methods=["GET"])
def api_get_message_template(sensor_key: str):
    """获取传感器的消息模板（含自动发送配置）。"""
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
    """更新自动发送配置（模式 + 固定内容）。"""
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
    logger.info(f"传感器 {sensor_key} 自动发送配置已更新: {updates}")
    return jsonify({"message": "自动发送配置已更新", "updates": updates})


@app.route("/api/sensors/generate-sample/<sensor_type>", methods=["GET"])
def api_generate_sample(sensor_type: str):
    """根据传感器类型生成示例消息体。"""
    from sender.data_generator import generate_sample_content
    device_id = request.args.get("deviceId", "SL-001")
    sample = generate_sample_content(sensor_type, device_id)
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
    sensor_mgr.stop_all()
    sensor_mgr.load_from_config()

    return jsonify({"message": "MQTT 配置已更新并重新连接"})


@app.route("/api/config/mqtt/reconnect", methods=["POST"])
def api_reconnect_mqtt():
    cfg = config_mgr.get_mqtt_config()
    mqtt_mgr.connect(cfg)
    sensor_mgr.load_from_config()
    return jsonify({"message": "MQTT 重连成功" if mqtt_mgr.is_connected else "MQTT 正在后台重连..."})


@app.route("/api/config/backend-url", methods=["PUT"])
def api_update_backend_url():
    """更新后端 API URL。"""
    data = request.get_json(force=True)
    url = data.get("backendUrl", "").strip()
    if not url:
        return jsonify({"error": "backendUrl 不能为空"}), 400
    config_mgr.set_backend_url(url)
    return jsonify({"message": f"后端 URL 已更新为 {url}"})


@app.route("/api/config/database")
def api_get_database_config():
    """获取数据库连接配置。"""
    cfg = config_mgr.get_database_config()
    cfg = dict(cfg)
    cfg["password"] = "******" if cfg.get("password") else ""
    return jsonify(cfg)


@app.route("/api/config/database", methods=["PUT"])
def api_update_database_config():
    """更新数据库连接配置并重新初始化连接。"""
    data = request.get_json(force=True)
    current = config_mgr.get_database_config()

    if data.get("password") in ("******", ""):
        data["password"] = current.get("password", "")

    ok = config_mgr.update_database_config(data)
    if not ok:
        return jsonify({"error": "保存数据库配置失败"}), 500

    # 热重连数据库
    global db_client
    from sender.db_client import DbClient
    try:
        if db_client:
            db_client.close()
        db_client = DbClient(config_mgr.get_database_config())
        sensor_mgr.set_db_client(db_client)
        logger.info("数据库连接已重新初始化")
    except Exception as e:
        logger.error(f"重连数据库失败: {e}")
        return jsonify({"warning": f"配置已保存但连接失败: {e}"}), 200

    return jsonify({"message": "数据库配置已更新并重新连接"})


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


# ================================ API: 配置指令 (模拟后端下发) ================================

@app.route("/api/mock-config/send", methods=["POST"])
def api_send_mock_config():
    data = request.get_json(force=True)
    action = data.get("action", "")
    device_id = data.get("deviceId", "")
    params = data.get("params", {})

    if not action:
        return jsonify({"error": "action 不能为空"}), 400

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
            # 查找该设备的所有传感器并更新
            for s in sensor_mgr.list_sensors():
                if s["deviceId"] == device_id:
                    sensor_mgr.update_sensor_config(s["sensorKey"], {"interval": interval})
            return True
        else:
            for s in sensor_mgr.list_sensors():
                sensor_mgr.update_sensor_config(s["sensorKey"], {"interval": interval})
            return True

    elif action == "set_data_range":
        data_range = {"min": params.get("min", 0), "max": params.get("max", 800)}
        if device_id:
            for s in sensor_mgr.list_sensors():
                if s["deviceId"] == device_id:
                    sensor_mgr.update_sensor_config(s["sensorKey"], {"dataRange": data_range})
            return True
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
            # 从 params 提取 sensorId
            sensor_id = params.get("sensorId", params.get("id"))
            if sensor_id is None:
                sensor_id = int(time.time() * 1000) % 100000
            key = sensor_mgr.add_sensor(device_id, params)
            return key is not None
        return False

    elif action == "remove_sensor":
        if device_id:
            sensor_id = params.get("sensorId")
            if sensor_id:
                key = _make_sensor_key(device_id, sensor_id)
                return sensor_mgr.remove_sensor(key)
            else:
                # 移除该设备的所有传感器
                for s in sensor_mgr.list_sensors():
                    if s["deviceId"] == device_id:
                        sensor_mgr.remove_sensor(s["sensorKey"])
                return True
        return False

    elif action == "stop_sensor":
        if device_id:
            sensor_id = params.get("sensorId")
            if sensor_id:
                key = _make_sensor_key(device_id, sensor_id)
                return sensor_mgr.stop_sensor(key)
            else:
                for s in sensor_mgr.list_sensors():
                    if s["deviceId"] == device_id:
                        sensor_mgr.stop_sensor(s["sensorKey"])
                return True
        return False

    elif action == "start_sensor":
        if device_id:
            sensor_id = params.get("sensorId")
            if sensor_id:
                key = _make_sensor_key(device_id, sensor_id)
                return sensor_mgr.start_sensor(key)
            else:
                for s in sensor_mgr.list_sensors():
                    if s["deviceId"] == device_id:
                        sensor_mgr.start_sensor(s["sensorKey"])
                return True
        return False

    else:
        logger.warning(f"未知配置指令: {action}")
        return False


# ================================ 启动 ================================

_start_time = 0.0


def main():
    global _start_time, config_mgr, mqtt_mgr, sensor_mgr, db_client

    _start_time = time.time()

    # 初始化组件
    config_mgr = ConfigManager()
    mqtt_mgr = MqttClientManager()

    # 初始化数据库连接池
    db_client = None
    db_cfg = config_mgr.get_database_config()
    if db_cfg and db_cfg.get("host"):
        try:
            from sender.db_client import DbClient
            db_client = DbClient(db_cfg)
            logger.info("数据库连接池已初始化")
        except Exception as e:
            logger.warning(f"数据库连接初始化失败: {e}")

    sensor_mgr = SensorManager(config_mgr, mqtt_mgr, db_client)

    # 注册 MQTT 回调
    mqtt_mgr.on_control_command = sensor_mgr.handle_control_command
    mqtt_mgr.on_mock_config = _handle_mock_config_command

    # 连接 MQTT
    mqtt_cfg = config_mgr.get_mqtt_config()
    mqtt_mgr.connect(mqtt_cfg)

    # 从本地配置加载传感器
    sensor_mgr.load_from_config()

    # 从数据库同步传感器（异步延迟执行，等待 MQTT 连接就绪）
    def _sync_from_backend():
        time.sleep(2)  # 等 MQTT 连接稳定
        if db_client:
            logger.info("尝试从数据库同步传感器...")
            sensor_mgr.load_from_backend()
        else:
            backend_url = config_mgr.get_backend_url()
            logger.info(f"数据库未连接，尝试从 REST API 同步: {backend_url}")
            sensor_mgr.load_from_backend()

    sync_thread = threading.Thread(target=_sync_from_backend, daemon=True)
    sync_thread.start()

    # 获取端口
    port = 5050
    db_status = "已连接" if db_client else "未连接 (使用 REST 回退)"
    logger.info("=" * 55)
    logger.info("  智慧路灯 Mock 模拟数据发送器")
    logger.info(f"  Web UI: http://localhost:{port}")
    logger.info(f"  MQTT:   {mqtt_cfg['broker']}:{mqtt_cfg['port']}")
    logger.info(f"  数据库: {db_status}")
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
        if db_client:
            db_client.close()
        logger.info("已安全退出")


if __name__ == "__main__":
    main()
