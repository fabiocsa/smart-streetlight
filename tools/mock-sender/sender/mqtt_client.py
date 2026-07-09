"""
MQTT 客户端模块 (v2 — 稳定版)
=============================
封装 paho-mqtt，提供：
  - 可靠连接 / 智能重连（clean_session=False 避免 session 碰撞）
  - 传感器数据发布（QoS 1 + inflight 流控）
  - 控制指令订阅 (streetlight/{deviceId}/control)
  - 配置指令订阅 (streetlight/mock-sender/config)
  - 线程安全发布 + 连接健康看门狗

rc=7 (MQTT_ERR_CONN_LOST) 根因与修复:
  1. clean_session=True 导致 EMQX 立即丢弃 session → 重连后 Client ID 碰撞 → 断连风暴
     → 改为 clean_session=False，EMQX 保留 session，平滑重连
  2. _subscribe_all_controls() 空实现 → 重连后控制指令订阅丢失
     → 追踪已订阅 device_id，重连时批量恢复
  3. 重连延迟 1s 过于激进 → min 3s / max 60s
  4. QoS 1 消息无流控 → max_inflight=10, max_queued=100
  5. keepalive=60 在弱网中偏短 → 120s
  6. 无连接健康监控 → 新增 30s 周期看门狗线程
"""

import json
import logging
import threading
import time
from typing import Any, Callable, Dict, List, Optional, Set

import paho.mqtt.client as mqtt

logger = logging.getLogger("mock-sender.mqtt")

# 控制指令 Topic 模板
TOPIC_CONTROL = "{prefix}/{device_id}/control"
TOPIC_CONTROL_RESPONSE = "{prefix}/{device_id}/control/response"
TOPIC_SENSOR_DATA = "{prefix}/{device_id}/sensor/data"
TOPIC_HEARTBEAT = "{prefix}/{device_id}/status"
TOPIC_MOCK_CONFIG = "{prefix}/mock-sender/config"

# MQTT 错误码可读描述
RC_DESCRIPTIONS = {
    0: "连接成功",
    1: "协议版本不支持",
    2: "Client ID 被拒",
    3: "Broker 不可用",
    4: "用户名或密码错误",
    5: "未授权",
    6: "连接超时",
    7: "TCP 连接丢失",
}


def _rc_desc(rc: int) -> str:
    return RC_DESCRIPTIONS.get(rc, f"未知错误码({rc})")


class MqttClientManager:
    """
    MQTT 客户端管理器 (v2)。

    回调注册:
        on_control_command(device_id, payload)
        on_mock_config(payload)
        on_connected()
        on_disconnected()
    """

    def __init__(self):
        self._client: Optional[mqtt.Client] = None
        self._config: Dict[str, Any] = {}
        self._lock = threading.Lock()

        # 回调
        self.on_control_command: Optional[Callable] = None
        self.on_mock_config: Optional[Callable] = None
        self.on_registration_ack: Optional[Callable] = None
        self.on_connected: Optional[Callable] = None
        self.on_disconnected: Optional[Callable] = None

        self._connected = False
        self._subscriptions: List[str] = []
        self._subscribed_devices: Set[str] = set()  # ★ 追踪已订阅设备

        # 重连计数
        self._reconnect_count = 0
        self._last_disconnect_time: float = 0.0

        # ★ 连接健康看门狗
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
        """
        连接到 MQTT Broker。如果已连接则先断开。

        mqtt_config 字段:
            broker, port, username, password, clientId, topicPrefix
        """
        self.disconnect()

        with self._lock:
            self._config = dict(mqtt_config)

        broker = mqtt_config["broker"]
        port = mqtt_config.get("port", 1883)
        username = mqtt_config.get("username", "")
        password = mqtt_config.get("password", "")
        client_id = mqtt_config.get("clientId", "mock-sender")

        # ★ 为 Client ID 添加随机后缀，避免多实例 / 残留 session 碰撞
        import random
        suffix = f"{int(time.time()) % 100000:05d}"
        unique_client_id = f"{client_id}-{suffix}"

        try:
            # ★ 核心修复: clean_session=False
            #   让 EMQX 保留 session（订阅、未确认消息），重连时平滑恢复
            client = mqtt.Client(
                client_id=unique_client_id,
                clean_session=False,
                protocol=mqtt.MQTTv311,
            )
            client.username_pw_set(username, password)

            # ★ QoS 1 流控: 限制在途消息和排队消息数量
            client.max_inflight_messages_set(10)   # 最多 10 条未确认 QoS 1 消息
            client.max_queued_messages_set(100)     # 最多排队 100 条

            # 回调
            client.on_connect = self._on_connect
            client.on_disconnect = self._on_disconnect
            client.on_message = self._on_message
            client.on_publish = self._on_publish

            # ★ 重连策略: min 3s ~ max 60s (避免 1s 激起重连导致 broker 限流)
            client.reconnect_delay_set(min_delay=3, max_delay=60)

            logger.info(f"正在连接 MQTT Broker: {broker}:{port} (clientId={unique_client_id})")

            # ★ keepalive 120s (网络波动容错性更好)
            client.connect(broker, port, keepalive=120)

            # ★ 先设置 client 再 start loop (防止 loop_start 后回调访问到 None client)
            with self._lock:
                self._client = client
            client.loop_start()

            # 等待连接建立
            for _ in range(50):  # 最多等 5 秒
                if self._connected:
                    break
                time.sleep(0.1)

            if self._connected:
                # 持久化 client ID (用于 reconnect 识别)
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
        """断开 MQTT 连接并清理资源。"""
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
        """线程安全地发布一条消息。"""
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

    def publish_sensor_data(self, device_id: str, payload: dict, data_topic: str = "") -> bool:
        """发布传感器数据到指定主题，若未指定则使用 streetlight/{deviceId}/sensor/data。"""
        if data_topic:
            topic = data_topic
        else:
            topic = self._topic(TOPIC_SENSOR_DATA, device_id)
        return self.publish(topic, payload)

    def publish_heartbeat(self, device_id: str, payload: dict) -> bool:
        """发布心跳到 streetlight/{deviceId}/status。"""
        topic = self._topic(TOPIC_HEARTBEAT, device_id)
        return self.publish(topic, payload)

    def publish_control_response(self, device_id: str, payload: dict) -> bool:
        """发布控制响应到 streetlight/{deviceId}/control/response。"""
        topic = self._topic(TOPIC_CONTROL_RESPONSE, device_id)
        return self.publish(topic, payload)

    # ------------------------------------------------------------------
    # 传感器注册 / 注销 (MQTT)
    # ------------------------------------------------------------------

    def publish_registration(self, payload: dict) -> bool:
        """[已废弃] 发布设备注册消息。设备仅通过 REST API 管理。"""
        prefix = self._config.get("topicPrefix", "streetlight")
        topic = f"{prefix}/register"
        return self.publish(topic, payload)

    def publish_deregistration(self, device_id: str) -> bool:
        """[已废弃] 发布设备注销消息。"""
        topic = self._topic("{prefix}/{device_id}/deregister", device_id)
        payload = {"deviceId": device_id, "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
        return self.publish(topic, payload)

    def publish_sensor_register(self, device_id: str, sensor_info: dict) -> bool:
        """发布传感器动态注册消息到 streetlight/{deviceId}/sensor/register。"""
        topic = self._topic("{prefix}/{device_id}/sensor/register", device_id)
        return self.publish(topic, sensor_info)

    def publish_sensor_unregister(self, device_id: str, sensor_id: int) -> bool:
        """发布传感器注销消息到 streetlight/{deviceId}/sensor/unregister。"""
        topic = self._topic("{prefix}/{device_id}/sensor/unregister", device_id)
        payload = {"deviceId": device_id, "sensorId": sensor_id,
                   "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
        return self.publish(topic, payload)

    # ------------------------------------------------------------------
    # 内部回调
    # ------------------------------------------------------------------

    def _on_connect(self, client, userdata, flags, rc):
        if rc == 0:
            self._connected = True
            self._last_ping_success = time.monotonic()
            # flags 包含 session_present 标志位
            session_present = flags.get("session present", 0) if isinstance(flags, dict) else 0
            logger.info(
                f"MQTT 已连接到 Broker (rc=0, session_present={session_present}, "
                f"reconnect_count={self._reconnect_count})"
            )

            # ★ 重连后恢复所有设备控制指令订阅
            self._restore_subscriptions()

            if self.on_connected:
                self.on_connected()
        else:
            self._connected = False
            logger.error(f"MQTT 连接被拒绝, rc={rc} ({_rc_desc(rc)})")

    def _on_disconnect(self, client, userdata, rc):
        self._connected = False
        self._reconnect_count += 1
        self._last_disconnect_time = time.monotonic()

        if rc != 0:
            logger.warning(
                f"MQTT 异常断开, rc={rc} ({_rc_desc(rc)}), "
                f"重连计数={self._reconnect_count}, "
                f"paho 将自动重连 (delay 3-60s)"
            )
        else:
            logger.info("MQTT 正常断开 (rc=0)")

        if self.on_disconnected:
            self.on_disconnected()

    def _on_message(self, client, userdata, msg):
        self._mark_activity()  # ★ 收到消息也标记活动
        try:
            payload = json.loads(msg.payload.decode())
            topic = msg.topic

            # 判断是否为 mock-sender 配置指令
            if topic.endswith("/mock-sender/config"):
                logger.info(f"收到配置指令: {payload}")
                if self.on_mock_config:
                    self.on_mock_config(payload)
                return

            # 判断是否为注册确认: streetlight/{deviceId}/register/ack
            if topic.endswith("/register/ack"):
                logger.info(f"收到注册确认: {payload}")
                if self.on_registration_ack:
                    self.on_registration_ack(topic, payload)
                return

            # 判断是否为控制指令: streetlight/{deviceId}/control
            parts = topic.split("/")
            if len(parts) >= 4 and parts[3] == "control":
                device_id = parts[1]
                logger.info(f"收到控制指令 - {device_id}: {payload}")
                if self.on_control_command:
                    self.on_control_command(device_id, payload)
                return

            logger.debug(f"收到未处理的消息 - topic: {topic}")
        except Exception as e:
            logger.error(f"处理消息失败: {e}")

    def _on_publish(self, client, userdata, mid):
        """QoS 1 消息发送确认回调 — 用于流控监控（当前仅记录 debug 日志）。"""
        logger.debug(f"消息已确认: mid={mid}")

    # ------------------------------------------------------------------
    # 订阅管理
    # ------------------------------------------------------------------

    def _topic(self, template: str, device_id: str) -> str:
        prefix = self._config.get("topicPrefix", "streetlight")
        return template.format(prefix=prefix, device_id=device_id)

    def _restore_subscriptions(self) -> None:
        """★ 重连后恢复所有订阅（全局 + 设备级）。"""
        # 全局订阅
        self._subscribe_mock_config()
        self._subscribe_registration_ack()

        # 设备级控制指令订阅
        with self._lock:
            devices = list(self._subscribed_devices)

        if devices:
            logger.info(f"正在恢复 {len(devices)} 个设备的控制指令订阅...")
            for device_id in devices:
                topic = self._topic(TOPIC_CONTROL, device_id)
                self._subscribe(topic)
            logger.info(f"设备订阅恢复完成 ({len(devices)} 个)")

    def _subscribe_all_controls(self) -> None:
        """遍历所有已跟踪设备并订阅控制指令（首次连接时由 restore 替代）。"""
        self._restore_subscriptions()

    def subscribe_device(self, device_id: str) -> bool:
        """订阅指定设备的控制指令主题。"""
        with self._lock:
            self._subscribed_devices.add(device_id)  # ★ 持久追踪
        topic = self._topic(TOPIC_CONTROL, device_id)
        return self._subscribe(topic)

    def unsubscribe_device(self, device_id: str) -> bool:
        """取消订阅指定设备的控制指令主题。"""
        with self._lock:
            self._subscribed_devices.discard(device_id)
        topic = self._topic(TOPIC_CONTROL, device_id)
        return self._unsubscribe(topic)

    def _subscribe_mock_config(self) -> bool:
        """订阅 Mock Sender 全局配置指令主题。"""
        prefix = self._config.get("topicPrefix", "streetlight")
        topic = f"{prefix}/mock-sender/config"
        return self._subscribe(topic)

    def _subscribe_registration_ack(self) -> bool:
        """订阅注册确认主题。"""
        prefix = self._config.get("topicPrefix", "streetlight")
        topic = f"{prefix}/+/register/ack"
        return self._subscribe(topic)

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
    # ★ 连接健康看门狗
    # ------------------------------------------------------------------

    def _start_watchdog(self) -> None:
        """启动连接健康监控线程（30s 周期）。"""
        if self._watchdog_running:
            return
        self._watchdog_running = True
        self._last_ping_success = time.monotonic()
        self._watchdog_thread = threading.Thread(
            target=self._watchdog_loop,
            name="mqtt-watchdog",
            daemon=True,
        )
        self._watchdog_thread.start()
        logger.debug("MQTT 看门狗已启动 (interval=30s)")

    def _stop_watchdog(self) -> None:
        """停止看门狗线程。"""
        self._watchdog_running = False
        if self._watchdog_thread and self._watchdog_thread.is_alive():
            self._watchdog_thread.join(timeout=2)
        logger.debug("MQTT 看门狗已停止")

    def _watchdog_loop(self) -> None:
        """
        每 30 秒检查连接健康状态。
        如果 connected 状态超过 90 秒无活动，触发强制重连。
        """
        while self._watchdog_running:
            time.sleep(30)

            with self._lock:
                client = self._client
                connected = self._connected

            if not client or not connected:
                # 未连接状态是正常的（paho 自动重连中），不干预
                continue

            # 检查最后活动时间
            idle_seconds = time.monotonic() - self._last_ping_success
            if idle_seconds > 90:
                logger.warning(
                    f"MQTT 连接健康检查失败: {idle_seconds:.0f}s 无活动，"
                    f"触发强制重连"
                )
                self._force_reconnect()

    def _force_reconnect(self) -> None:
        """强制断开并重新连接 MQTT Broker（由看门狗或外部调用）。"""
        # ★ 先停止当前看门狗（避免双线程）
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

        # 等待 2 秒让旧连接完全清理
        time.sleep(2)

        # 重新连接（connect 内部会启动新的看门狗）
        try:
            self.connect(dict(self._config))
            logger.info("看门狗强制重连完成")
        except Exception as e:
            logger.error(f"看门狗强制重连失败: {e}")

    def _mark_activity(self) -> None:
        """更新最近活动时间戳（每次成功发布或收到消息时调用）。"""
        self._last_ping_success = time.monotonic()
