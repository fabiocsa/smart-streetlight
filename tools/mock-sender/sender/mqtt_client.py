"""
MQTT 客户端模块
=============
封装 paho-mqtt，提供：
  - 连接 / 断线重连（paho 内置 + 自愈回调）
  - 传感器数据发布
  - 控制指令订阅 (streetlight/{deviceId}/control)
  - 配置指令订阅 (streetlight/mock-sender/config)
  - 线程安全发布
"""

import json
import logging
import threading
import time
from typing import Any, Callable, Dict, List, Optional

import paho.mqtt.client as mqtt

logger = logging.getLogger("mock-sender.mqtt")

# 控制指令 Topic 模板
TOPIC_CONTROL = "{prefix}/{device_id}/control"
TOPIC_CONTROL_RESPONSE = "{prefix}/{device_id}/control/response"
TOPIC_SENSOR_DATA = "{prefix}/{device_id}/sensor/data"
TOPIC_HEARTBEAT = "{prefix}/{device_id}/status"
TOPIC_MOCK_CONFIG = "{prefix}/mock-sender/config"


class MqttClientManager:
    """
    MQTT 客户端管理器。

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

    # ------------------------------------------------------------------
    # 公共属性
    # ------------------------------------------------------------------

    @property
    def is_connected(self) -> bool:
        return self._connected

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

        try:
            client = mqtt.Client(
                client_id=client_id,
                protocol=mqtt.MQTTv311,
            )
            client.username_pw_set(username, password)
            client.on_connect = self._on_connect
            client.on_disconnect = self._on_disconnect
            client.on_message = self._on_message

            # 启用自动重连 (paho 内置)
            client.reconnect_delay_set(min_delay=1, max_delay=30)

            logger.info(f"正在连接 MQTT Broker: {broker}:{port}")
            client.connect(broker, port, keepalive=60)
            client.loop_start()

            with self._lock:
                self._client = client

            # 等待连接建立
            for _ in range(50):  # 最多等 5 秒
                if self._connected:
                    break
                time.sleep(0.1)

            if self._connected:
                logger.info("MQTT 连接成功")
                return True
            else:
                logger.warning("MQTT 连接超时，将在后台重试")
                return False

        except Exception as e:
            logger.error(f"MQTT 连接失败: {e}")
            return False

    def disconnect(self) -> None:
        """断开 MQTT 连接并清理资源。"""
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
            if not client or not self._connected:
                return False
            try:
                msg = json.dumps(payload, ensure_ascii=False)
                result = client.publish(topic, msg, qos=qos)
                if result.rc == mqtt.MQTT_ERR_SUCCESS:
                    return True
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
    # 设备注册 / 注销
    # ------------------------------------------------------------------

    def publish_registration(self, payload: dict) -> bool:
        """发布设备注册消息到 streetlight/register。"""
        prefix = self._config.get("topicPrefix", "streetlight")
        topic = f"{prefix}/register"
        return self.publish(topic, payload)

    def publish_deregistration(self, device_id: str) -> bool:
        """发布设备注销消息到 streetlight/{deviceId}/deregister。"""
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
            logger.info("MQTT 已连接到 Broker")
            self._subscribe_all_controls()
            self._subscribe_mock_config()
            self._subscribe_registration_ack()
            if self.on_connected:
                self.on_connected()
        else:
            logger.error(f"MQTT 连接失败, rc={rc}")

    def _on_disconnect(self, client, userdata, rc):
        self._connected = False
        logger.warning(f"MQTT 断开连接, rc={rc}")
        if self.on_disconnected:
            self.on_disconnected()

    def _on_message(self, client, userdata, msg):
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

    # ------------------------------------------------------------------
    # 订阅管理
    # ------------------------------------------------------------------

    def _topic(self, template: str, device_id: str) -> str:
        prefix = self._config.get("topicPrefix", "streetlight")
        return template.format(prefix=prefix, device_id=device_id)

    def _subscribe_all_controls(self) -> None:
        """遍历所有已配传感器并订阅控制指令。"""
        # 注意：传感器列表在 SensorManager 中，这里由外部调用 subscribe_device
        pass

    def subscribe_device(self, device_id: str) -> bool:
        """订阅指定设备的控制指令主题。"""
        topic = self._topic(TOPIC_CONTROL, device_id)
        return self._subscribe(topic)

    def unsubscribe_device(self, device_id: str) -> bool:
        """取消订阅指定设备的控制指令主题。"""
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
