"""
MQTT 客户端模块 (v4 — 传感器独立，去设备化)
=============================
封装 paho-mqtt，提供：
  - 可靠连接 / 智能重连（clean_session=False 避免 session 碰撞）
  - 传感器数据发布（QoS 1 + inflight 流控）— 使用传感器自身 ID
  - 传感器控制指令订阅 (streetlight/sensor/{sensorId}/cmd)
  - 线程安全发布 + 连接健康看门狗

v4 变更:
  - 删除所有设备级 topic（TOPIC_CONTROL, TOPIC_CONTROL_RESPONSE）
  - 新增传感器 cmd topic: streetlight/sensor/{sensorId}/cmd
  - 新增传感器 cmd response topic: streetlight/sensor/{sensorId}/cmd/response
  - 删除 mock-sender/config 主题订阅（前后端隔离）
  - 回调简化: on_sensor_cmd(sensor_id, payload)
"""

import json
import logging
import threading
import time
from typing import Any, Callable, Dict, List, Optional

import paho.mqtt.client as mqtt

logger = logging.getLogger("mock-sender.mqtt")

# Topic 模板
TOPIC_SENSOR_DATA = "{prefix}/sensor/{sensor_id}/data"
TOPIC_HEARTBEAT = "{prefix}/sensor/{sensor_id}/status"
TOPIC_SENSOR_CMD = "{prefix}/sensor/{sensor_id}/cmd"
TOPIC_SENSOR_CMD_RESPONSE = "{prefix}/sensor/{sensor_id}/cmd/response"
TOPIC_SENSOR_REGISTER = "{prefix}/sensor/register"
TOPIC_SENSOR_UNREGISTER = "{prefix}/sensor/unregister"

# MQTT 错误码可读描述
RC_DESCRIPTIONS = {
    0: "连接成功", 1: "协议版本不支持", 2: "Client ID 被拒",
    3: "Broker 不可用", 4: "用户名或密码错误", 5: "未授权",
    6: "连接超时", 7: "TCP 连接丢失",
}


def _rc_desc(rc: int) -> str:
    return RC_DESCRIPTIONS.get(rc, f"未知错误码({rc})")


class MqttClientManager:
    """
    MQTT 客户端管理器 (v4 — 传感器独立，去设备化)。

    回调注册:
        on_sensor_cmd(sensor_id, payload)   — 收到传感器控制指令
        on_connected()
        on_disconnected()
    """

    def __init__(self):
        self._client: Optional[mqtt.Client] = None
        self._config: Dict[str, Any] = {}
        self._lock = threading.Lock()

        # 回调
        self.on_sensor_cmd: Optional[Callable] = None
        self.on_connected: Optional[Callable] = None
        self.on_disconnected: Optional[Callable] = None

        self._connected = False
        self._subscriptions: List[str] = []
        self._subscribed_sensor_cmds: set = set()  # 已订阅 cmd 的 sensor_id 集合

        # 重连计数
        self._reconnect_count = 0

        # 连接健康看门狗
        self._watchdog_running = False
        self._watchdog_thread: Optional[threading.Thread] = None
        self._last_ping_success: float = 0.0

    # ------------------------------------------------------------------
    # 公共属性
    # ------------------------------------------------------------------

    @property
    def is_connected(self) -> bool:
        return self._connected

    @property
    def reconnect_count(self) -> int:
        return self._reconnect_count

    # ------------------------------------------------------------------
    # 连接 / 断开
    # ------------------------------------------------------------------

    def connect(self, mqtt_config: Dict[str, Any]) -> bool:
        self.disconnect()

        with self._lock:
            self._config = dict(mqtt_config)

        broker = mqtt_config["broker"]
        port = mqtt_config.get("port", 1883)
        username = mqtt_config.get("username", "")
        password = mqtt_config.get("password", "")
        client_id = mqtt_config.get("clientId", "mock-sender")

        import random
        suffix = f"{int(time.time()) % 100000:05d}"
        unique_client_id = f"{client_id}-{suffix}"

        try:
            client = mqtt.Client(
                client_id=unique_client_id,
                clean_session=False,
                protocol=mqtt.MQTTv311,
            )
            client.username_pw_set(username, password)

            client.max_inflight_messages_set(10)
            client.max_queued_messages_set(100)

            client.on_connect = self._on_connect
            client.on_disconnect = self._on_disconnect
            client.on_message = self._on_message
            client.on_publish = self._on_publish

            client.reconnect_delay_set(min_delay=3, max_delay=60)

            logger.info(f"正在连接 MQTT Broker: {broker}:{port} (clientId={unique_client_id})")
            client.connect(broker, port, keepalive=120)

            with self._lock:
                self._client = client
            client.loop_start()

            for _ in range(50):
                if self._connected:
                    break
                time.sleep(0.1)

            if self._connected:
                self._config["_effectiveClientId"] = unique_client_id
                logger.info(f"MQTT 连接成功 (clientId={unique_client_id})")
                self._start_watchdog()
                return True
            else:
                logger.warning("MQTT 连接超时，将在后台重试")
                self._start_watchdog()
                return False

        except Exception as e:
            logger.error(f"MQTT 连接失败: {e}")
            return False

    def disconnect(self) -> None:
        self._stop_watchdog()
        with self._lock:
            client = self._client
            self._client = None
            self._connected = False
        if client:
            try:
                client.loop_stop()
                client.disconnect()
                logger.info("MQTT 已断开")
            except Exception:
                pass

    # ------------------------------------------------------------------
    # 发布
    # ------------------------------------------------------------------

    def publish(self, topic: str, payload: dict, qos: int = 1) -> bool:
        with self._lock:
            client = self._client
            connected = self._connected
        if not client or not connected:
            return False
        try:
            msg = json.dumps(payload, ensure_ascii=False)
            result = client.publish(topic, msg, qos=qos)
            if result.rc == mqtt.MQTT_ERR_SUCCESS:
                self._mark_activity()
                return True
            if result.rc == mqtt.MQTT_ERR_QUEUE_SIZE:
                logger.warning(f"消息队列已满, topic={topic}")
            else:
                logger.warning(f"发布失败, rc={result.rc}, topic={topic}")
            return False
        except Exception as e:
            logger.error(f"发布异常: {e}")
            return False

    def publish_sensor_data(self, sensor_id: int, payload: dict) -> bool:
        """发布传感器数据到 streetlight/sensor/{sensorId}/data (v4: 不再含 deviceId)。"""
        prefix = self._config.get("topicPrefix", "streetlight")
        topic = f"{prefix}/sensor/{sensor_id}/data"
        return self.publish(topic, payload)

    def publish_heartbeat(self, sensor_id: int, payload: dict) -> bool:
        """发布心跳到 streetlight/sensor/{sensorId}/status。"""
        prefix = self._config.get("topicPrefix", "streetlight")
        topic = f"{prefix}/sensor/{sensor_id}/status"
        return self.publish(topic, payload)

    def publish_cmd_response(self, sensor_id: int, payload: dict) -> bool:
        """发布控制响应到 streetlight/sensor/{sensorId}/cmd/response。"""
        prefix = self._config.get("topicPrefix", "streetlight")
        topic = f"{prefix}/sensor/{sensor_id}/cmd/response"
        return self.publish(topic, payload)

    # ------------------------------------------------------------------
    # 传感器注册 / 注销 (MQTT)
    # ------------------------------------------------------------------

    def publish_sensor_register(self, sensor_info: dict) -> bool:
        prefix = self._config.get("topicPrefix", "streetlight")
        topic = f"{prefix}/sensor/register"
        return self.publish(topic, sensor_info)

    def publish_sensor_unregister(self, sensor_id: int) -> bool:
        prefix = self._config.get("topicPrefix", "streetlight")
        topic = f"{prefix}/sensor/unregister"
        payload = {"sensorId": sensor_id,
                   "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
        return self.publish(topic, payload)

    # ------------------------------------------------------------------
    # 传感器 cmd 订阅管理
    # ------------------------------------------------------------------

    def subscribe_sensor_cmd(self, sensor_id: int) -> bool:
        """订阅 streetlight/sensor/{sensorId}/cmd — 接收后端下发的控制指令。"""
        with self._lock:
            self._subscribed_sensor_cmds.add(sensor_id)
        prefix = self._config.get("topicPrefix", "streetlight")
        topic = f"{prefix}/sensor/{sensor_id}/cmd"
        return self._subscribe(topic)

    def unsubscribe_sensor_cmd(self, sensor_id: int) -> bool:
        """取消订阅传感器的控制指令主题。"""
        with self._lock:
            self._subscribed_sensor_cmds.discard(sensor_id)
        prefix = self._config.get("topicPrefix", "streetlight")
        topic = f"{prefix}/sensor/{sensor_id}/cmd"
        return self._unsubscribe(topic)

    def _restore_cmd_subscriptions(self) -> None:
        """重连后恢复所有传感器的 cmd 订阅。"""
        with self._lock:
            sensor_ids = list(self._subscribed_sensor_cmds)
        if sensor_ids:
            logger.info(f"正在恢复 {len(sensor_ids)} 个传感器的 cmd 订阅...")
            for sid in sensor_ids:
                prefix = self._config.get("topicPrefix", "streetlight")
                topic = f"{prefix}/sensor/{sid}/cmd"
                self._subscribe(topic)
            logger.info(f"传感器 cmd 订阅恢复完成 ({len(sensor_ids)} 个)")

    # ------------------------------------------------------------------
    # 内部回调
    # ------------------------------------------------------------------

    def _on_connect(self, client, userdata, flags, rc):
        if rc == 0:
            self._connected = True
            self._last_ping_success = time.monotonic()
            session_present = flags.get("session present", 0) if isinstance(flags, dict) else 0
            logger.info(
                f"MQTT 已连接到 Broker (rc=0, session_present={session_present}, "
                f"reconnect_count={self._reconnect_count})"
            )
            # 重连后恢复所有传感器 cmd 订阅
            self._restore_cmd_subscriptions()
            if self.on_connected:
                self.on_connected()
        else:
            self._connected = False
            logger.error(f"MQTT 连接被拒绝, rc={rc} ({_rc_desc(rc)})")

    def _on_disconnect(self, client, userdata, rc):
        self._connected = False
        self._reconnect_count += 1
        if rc != 0:
            logger.warning(
                f"MQTT 异常断开, rc={rc} ({_rc_desc(rc)}), "
                f"重连计数={self._reconnect_count}, paho 将自动重连 (delay 3-60s)"
            )
        else:
            logger.info("MQTT 正常断开 (rc=0)")
        if self.on_disconnected:
            self.on_disconnected()

    def _on_message(self, client, userdata, msg):
        self._mark_activity()
        try:
            payload = json.loads(msg.payload.decode())
            topic = msg.topic

            # v4: 传感器控制指令 — streetlight/sensor/{sensorId}/cmd
            parts = topic.split("/")
            if len(parts) >= 4 and parts[1] == "sensor" and parts[3] == "cmd":
                try:
                    sensor_id = int(parts[2])
                except ValueError:
                    logger.warning(f"无法解析 sensorId from topic: {topic}")
                    return
                logger.info(f"收到传感器控制指令 - sensorId={sensor_id}: {payload}")
                if self.on_sensor_cmd:
                    self.on_sensor_cmd(sensor_id, payload)
                return

            logger.debug(f"收到未处理的消息 - topic: {topic}")
        except Exception as e:
            logger.error(f"处理消息失败: {e}")

    def _on_publish(self, client, userdata, mid):
        logger.debug(f"消息已确认: mid={mid}")

    def _subscribe(self, topic: str, qos: int = 1) -> bool:
        with self._lock:
            client = self._client
            if not client or not self._connected:
                return False
            try:
                client.subscribe(topic, qos)
                self._subscriptions.append(topic)
                logger.info(f"已订阅: {topic}")
                return True
            except Exception as e:
                logger.error(f"订阅失败 {topic}: {e}")
                return False

    def _unsubscribe(self, topic: str) -> bool:
        with self._lock:
            client = self._client
            if not client or not self._connected:
                return False
            try:
                client.unsubscribe(topic)
                if topic in self._subscriptions:
                    self._subscriptions.remove(topic)
                logger.info(f"已取消订阅: {topic}")
                return True
            except Exception as e:
                logger.error(f"取消订阅失败 {topic}: {e}")
                return False

    # ------------------------------------------------------------------
    # 连接健康看门狗
    # ------------------------------------------------------------------

    def _start_watchdog(self) -> None:
        if self._watchdog_running:
            return
        self._watchdog_running = True
        self._last_ping_success = time.monotonic()
        self._watchdog_thread = threading.Thread(
            target=self._watchdog_loop, name="mqtt-watchdog", daemon=True,
        )
        self._watchdog_thread.start()
        logger.debug("MQTT 看门狗已启动 (interval=30s)")

    def _stop_watchdog(self) -> None:
        self._watchdog_running = False
        if self._watchdog_thread and self._watchdog_thread.is_alive():
            self._watchdog_thread.join(timeout=2)
        logger.debug("MQTT 看门狗已停止")

    def _watchdog_loop(self) -> None:
        while self._watchdog_running:
            time.sleep(30)
            with self._lock:
                client = self._client
                connected = self._connected
            if not client or not connected:
                continue
            idle_seconds = time.monotonic() - self._last_ping_success
            if idle_seconds > 90:
                logger.warning(
                    f"MQTT 连接健康检查失败: {idle_seconds:.0f}s 无活动，触发强制重连"
                )
                self._force_reconnect()

    def _force_reconnect(self) -> None:
        self._stop_watchdog()
        with self._lock:
            client = self._client
            self._client = None
            self._connected = False
        if client:
            try:
                client.loop_stop()
                client.disconnect()
            except Exception:
                pass
        time.sleep(2)
        try:
            self.connect(dict(self._config))
            logger.info("看门狗强制重连完成")
        except Exception as e:
            logger.error(f"看门狗强制重连失败: {e}")

    def _mark_activity(self) -> None:
        self._last_ping_success = time.monotonic()
