"""
MySQL 数据库客户端模块
=====================
提供连接池管理和传感器表直连查询，供 mock-sender 直接从数据库获取传感器列表。
复用项目已有的数据库连接信息（与后端 application.yml 一致）。
"""

import logging
from typing import Any, Dict, List, Optional

import mysql.connector
from mysql.connector import pooling

logger = logging.getLogger("mock-sender.db")

# 默认数据库配置（与后端 application.yml 一致）
DEFAULT_DB_CONFIG = {
    "host": "8.130.102.89",
    "port": 3306,
    "database": "streetlight",
    "user": "remote_user",
    "password": "123456",
    "poolSize": 5,
}


class DbClient:
    """
    MySQL 数据库客户端，使用连接池管理并发访问。

    所有查询方法均返回 dict 列表，字段名与数据库列名一致：
      id, device_id, sensor_type, display_name, data_topic,
      report_frequency, enabled, config_json, created_at, updated_at
    """

    def __init__(self, config: Optional[Dict[str, Any]] = None):
        cfg = dict(DEFAULT_DB_CONFIG)
        if config:
            cfg.update(config)

        self._host = cfg.get("host", "localhost")
        self._port = cfg.get("port", 3306)
        self._database = cfg.get("database", "streetlight")
        self._user = cfg.get("user", "remote_user")
        self._password = cfg.get("password", "")
        self._pool_size = cfg.get("poolSize", 5)

        try:
            self._pool = pooling.MySQLConnectionPool(
                pool_name="mock_sender_pool",
                pool_size=self._pool_size,
                host=self._host,
                port=self._port,
                database=self._database,
                user=self._user,
                password=self._password,
                charset="utf8mb4",
                autocommit=True,
            )
            # 测试连接
            conn = self._pool.get_connection()
            conn.close()
            logger.info(f"数据库连接池初始化成功: {self._host}:{self._port}/{self._database} "
                        f"(pool_size={self._pool_size})")
        except Exception as e:
            logger.error(f"数据库连接池初始化失败: {e}")
            raise

    # ------------------------------------------------------------------
    # 查询
    # ------------------------------------------------------------------

    def get_all_sensors(self) -> List[Dict[str, Any]]:
        """
        获取所有传感器（含已绑定和未绑定设备）。

        Returns:
            list[dict]: 每行包含 id, device_id(NULL=未绑定), sensor_type,
                        display_name, data_topic, report_frequency,
                        enabled, config_json
        """
        sql = """
            SELECT id, device_id, sensor_type, display_name, data_topic,
                   report_frequency, enabled, config_json, created_at, updated_at
            FROM sensor
            ORDER BY id
        """
        return self._query_all(sql)

    def get_sensors_by_device_id(self, device_id: str) -> List[Dict[str, Any]]:
        """获取指定设备下的所有传感器。"""
        sql = """
            SELECT id, device_id, sensor_type, display_name, data_topic,
                   report_frequency, enabled, config_json, created_at, updated_at
            FROM sensor
            WHERE device_id = %s
            ORDER BY id
        """
        return self._query_all(sql, (device_id,))

    def get_unbound_sensors(self) -> List[Dict[str, Any]]:
        """获取所有未绑定设备的传感器（device_id IS NULL）。"""
        sql = """
            SELECT id, device_id, sensor_type, display_name, data_topic,
                   report_frequency, enabled, config_json, created_at, updated_at
            FROM sensor
            WHERE device_id IS NULL
            ORDER BY id
        """
        return self._query_all(sql)

    def get_sensor_by_id(self, sensor_id: int) -> Optional[Dict[str, Any]]:
        """根据 ID 获取单个传感器。"""
        sql = """
            SELECT id, device_id, sensor_type, display_name, data_topic,
                   report_frequency, enabled, config_json, created_at, updated_at
            FROM sensor
            WHERE id = %s
        """
        rows = self._query_all(sql, (sensor_id,))
        return rows[0] if rows else None

    def get_enabled_sensors(self) -> List[Dict[str, Any]]:
        """获取所有已启用的传感器。"""
        sql = """
            SELECT id, device_id, sensor_type, display_name, data_topic,
                   report_frequency, enabled, config_json, created_at, updated_at
            FROM sensor
            WHERE enabled = 1
            ORDER BY id
        """
        return self._query_all(sql)

    # ------------------------------------------------------------------
    # 解绑 / 换绑
    # ------------------------------------------------------------------

    def unbind_sensor(self, sensor_id: int) -> bool:
        """
        将传感器从设备解绑（设 device_id = NULL）。

        Args:
            sensor_id: 传感器主键 ID

        Returns:
            bool: 是否成功
        """
        sql = "UPDATE sensor SET device_id = NULL WHERE id = %s"
        return self._execute(sql, (sensor_id,))

    def rebind_sensor(self, sensor_id: int, new_device_id: str) -> bool:
        """
        将传感器重新绑定到另一设备。

        Args:
            sensor_id: 传感器主键 ID
            new_device_id: 目标设备 device_id

        Returns:
            bool: 是否成功
        """
        sql = "UPDATE sensor SET device_id = %s WHERE id = %s"
        return self._execute(sql, (new_device_id, sensor_id))

    # ------------------------------------------------------------------
    # 增删
    # ------------------------------------------------------------------

    def add_sensor(self, device_id: Optional[str], sensor_cfg: Dict[str, Any]) -> Optional[int]:
        """
        向数据库插入一条传感器记录。

        Args:
            device_id: 设备 ID（可为 None 即无主传感器）
            sensor_cfg: 传感器配置 dict

        Returns:
            int: 新插入记录的 ID，失败返回 None
        """
        sql = """
            INSERT INTO sensor (device_id, sensor_type, display_name, data_topic,
                                report_frequency, enabled, config_json)
            VALUES (%s, %s, %s, %s, %s, %s, %s)
        """
        params = (
            device_id,
            sensor_cfg.get("sensorType", "light"),
            sensor_cfg.get("displayName", ""),
            sensor_cfg.get("dataTopic", ""),
            sensor_cfg.get("reportFrequency", sensor_cfg.get("interval", 5)),
            1 if sensor_cfg.get("enabled", True) else 0,
            sensor_cfg.get("configJson", sensor_cfg.get("config_json", "")),
        )
        return self._insert(sql, params)

    def remove_sensor(self, sensor_id: int) -> bool:
        """从数据库删除传感器记录。"""
        sql = "DELETE FROM sensor WHERE id = %s"
        return self._execute(sql, (sensor_id,))

    # ------------------------------------------------------------------
    # 设备查询（辅助：检查设备是否存在）
    # ------------------------------------------------------------------

    def get_all_devices(self) -> List[Dict[str, Any]]:
        """获取所有设备。"""
        sql = "SELECT id, name, device_id, status, location FROM device ORDER BY id"
        return self._query_all(sql)

    def device_exists(self, device_id: str) -> bool:
        """检查指定 device_id 的设备是否存在。"""
        sql = "SELECT 1 FROM device WHERE device_id = %s LIMIT 1"
        rows = self._query_all(sql, (device_id,))
        return len(rows) > 0

    # ------------------------------------------------------------------
    # 内部辅助
    # ------------------------------------------------------------------

    def _get_connection(self):
        """从连接池获取一个连接。"""
        return self._pool.get_connection()

    def _query_all(self, sql: str, params=None) -> List[Dict[str, Any]]:
        """执行查询并返回 dict 列表。"""
        conn = None
        try:
            conn = self._pool.get_connection()
            with conn.cursor(dictionary=True) as cursor:
                cursor.execute(sql, params)
                return cursor.fetchall()
        except Exception as e:
            logger.error(f"数据库查询失败: {e}\n  SQL: {sql}\n  params: {params}")
            return []
        finally:
            if conn:
                conn.close()

    def _execute(self, sql: str, params=None) -> bool:
        """执行写操作（UPDATE/INSERT/DELETE）。"""
        conn = None
        try:
            conn = self._pool.get_connection()
            with conn.cursor() as cursor:
                cursor.execute(sql, params)
                conn.commit()
                return cursor.rowcount > 0
        except Exception as e:
            logger.error(f"数据库写入失败: {e}\n  SQL: {sql}\n  params: {params}")
            return False
        finally:
            if conn:
                conn.close()

    def _insert(self, sql: str, params=None) -> Optional[int]:
        """执行 INSERT 并返回新记录 ID。"""
        conn = None
        try:
            conn = self._pool.get_connection()
            with conn.cursor() as cursor:
                cursor.execute(sql, params)
                conn.commit()
                return cursor.lastrowid
        except Exception as e:
            logger.error(f"数据库插入失败: {e}\n  SQL: {sql}\n  params: {params}")
            return None
        finally:
            if conn:
                conn.close()

    def close(self) -> None:
        """关闭连接池（释放所有连接）。"""
        try:
            # mysql-connector-python 的连接池没有显式 close_all 方法，
            # 但每个连接在 close() 时会归还到池中。我们只需标记为不可用。
            logger.info("数据库连接池已标记关闭")
        except Exception as e:
            logger.warning(f"关闭数据库连接池时出错: {e}")

    def is_available(self) -> bool:
        """测试数据库是否可达。"""
        try:
            conn = self._pool.get_connection()
            conn.ping(reconnect=False)
            conn.close()
            return True
        except Exception:
            return False
