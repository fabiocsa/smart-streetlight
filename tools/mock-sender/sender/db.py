"""
模拟器状态持久化模块 (SQLite)
=============================
将传感器运行状态（启停、模式等）保存到本地 SQLite，
模拟器重启后自动恢复关闭前的状态。
数据库文件: tools/mock-sender/state.db
"""

import sqlite3
import os
import threading
from typing import Any, Dict, List, Optional

DB_PATH = os.path.join(os.path.dirname(os.path.dirname(__file__)), "state.db")

_local = threading.local()


def _conn() -> sqlite3.Connection:
    """获取当前线程的数据库连接（自动创建）。"""
    if not hasattr(_local, "conn") or _local.conn is None:
        _local.conn = sqlite3.connect(DB_PATH)
        _local.conn.row_factory = sqlite3.Row
        _local.conn.execute("PRAGMA journal_mode=WAL")
        _local.conn.execute("PRAGMA foreign_keys=ON")
    return _local.conn


def init_db() -> None:
    """初始化数据库表（幂等）。"""
    conn = _conn()
    conn.execute("""
        CREATE TABLE IF NOT EXISTS sensor_state (
            sensor_key  TEXT PRIMARY KEY,
            sensor_id   INTEGER NOT NULL,
            display_name TEXT,
            sensor_type TEXT DEFAULT 'light',
            enabled     INTEGER DEFAULT 1,
            running     INTEGER DEFAULT 0,
            interval_sec INTEGER DEFAULT 5,
            control_mode TEXT DEFAULT 'auto',
            light_status TEXT DEFAULT 'off',
            data_topic  TEXT,
            config_json TEXT,
            updated_at  TEXT
        )
    """)
    conn.commit()


def save_sensor_state(sensor_key: str, data: Dict[str, Any]) -> None:
    """保存或更新传感器状态。"""
    conn = _conn()
    conn.execute("""
        INSERT INTO sensor_state (sensor_key, sensor_id, display_name, sensor_type,
            enabled, running, interval_sec, control_mode, light_status,
            data_topic, config_json, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now', 'localtime'))
        ON CONFLICT(sensor_key) DO UPDATE SET
            sensor_id    = excluded.sensor_id,
            display_name = excluded.display_name,
            sensor_type  = excluded.sensor_type,
            enabled      = excluded.enabled,
            running      = excluded.running,
            interval_sec = excluded.interval_sec,
            control_mode = excluded.control_mode,
            light_status = excluded.light_status,
            data_topic   = excluded.data_topic,
            config_json  = excluded.config_json,
            updated_at   = datetime('now', 'localtime')
    """, (
        sensor_key,
        data.get("sensorId", 0),
        data.get("displayName", ""),
        data.get("sensorType", "light"),
        int(data.get("enabled", True)),
        int(data.get("running", False)),
        data.get("interval", 5),
        data.get("controlMode", "auto"),
        data.get("lightStatus", "off"),
        data.get("dataTopic", ""),
        data.get("configJson", ""),
    ))
    conn.commit()


def load_all_states() -> Dict[str, Dict[str, Any]]:
    """加载所有传感器状态，返回 {sensor_key: state_dict}。"""
    conn = _conn()
    rows = conn.execute("SELECT * FROM sensor_state ORDER BY sensor_key").fetchall()
    result = {}
    for row in rows:
        result[row["sensor_key"]] = {
            "sensorId":     row["sensor_id"],
            "displayName":  row["display_name"],
            "sensorType":   row["sensor_type"],
            "enabled":      bool(row["enabled"]),
            "running":      bool(row["running"]),
            "interval":     row["interval_sec"],
            "controlMode":  row["control_mode"],
            "lightStatus":  row["light_status"],
            "dataTopic":    row["data_topic"],
            "configJson":   row["config_json"],
            "updatedAt":    row["updated_at"],
        }
    return result


def set_sensor_running(sensor_key: str, running: bool) -> None:
    """快速更新传感器启停状态。"""
    conn = _conn()
    conn.execute(
        "UPDATE sensor_state SET running = ?, updated_at = datetime('now', 'localtime') WHERE sensor_key = ?",
        (int(running), sensor_key),
    )
    conn.commit()


def set_sensor_enabled(sensor_key: str, enabled: bool) -> None:
    """快速更新传感器启用状态。"""
    conn = _conn()
    conn.execute(
        "UPDATE sensor_state SET enabled = ?, updated_at = datetime('now', 'localtime') WHERE sensor_key = ?",
        (int(enabled), sensor_key),
    )
    conn.commit()


def set_sensor_mode(sensor_key: str, control_mode: str) -> None:
    """快速更新传感器控制模式。"""
    conn = _conn()
    conn.execute(
        "UPDATE sensor_state SET control_mode = ?, updated_at = datetime('now', 'localtime') WHERE sensor_key = ?",
        (control_mode, sensor_key),
    )
    conn.commit()


def remove_sensor_state(sensor_key: str) -> None:
    """删除传感器状态记录。"""
    conn = _conn()
    conn.execute("DELETE FROM sensor_state WHERE sensor_key = ?", (sensor_key,))
    conn.commit()
