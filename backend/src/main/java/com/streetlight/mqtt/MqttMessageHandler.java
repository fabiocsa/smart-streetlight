package com.streetlight.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streetlight.service.ControlService;
import com.streetlight.service.DeviceService;
import com.streetlight.service.SensorDataService;
import com.streetlight.websocket.WebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@Slf4j
public class MqttMessageHandler implements MqttCallback {

    private final SensorDataService sensorDataService;
    private final ControlService controlService;
    private final DeviceService deviceService;
    private final MqttClientManager mqttClientManager;
    private final WebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MqttMessageHandler(SensorDataService sensorDataService,
                               ControlService controlService,
                               @Lazy DeviceService deviceService,
                               @Lazy MqttClientManager mqttClientManager,
                               WebSocketHandler webSocketHandler) {
        this.sensorDataService = sensorDataService;
        this.controlService = controlService;
        this.deviceService = deviceService;
        this.mqttClientManager = mqttClientManager;
        this.webSocketHandler = webSocketHandler;
    }

    @Override
    public void disconnected(MqttDisconnectResponse disconnectResponse) {
        log.warn("MQTT连接断开: {}", disconnectResponse.getReasonString());
    }

    @Override
    public void mqttErrorOccurred(MqttException exception) {
        log.warn("MQTT错误: {}", exception.getMessage());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String payload = new String(message.getPayload());
        log.info("收到MQTT消息 - topic: {}, payload: {}", topic, payload);
        try {
            if (mqttClientManager.isSensorDataTopic(topic)) {
                handleSensorData(topic, payload);
            } else if (mqttClientManager.isControlResponseTopic(topic)) {
                handleControlResponse(topic, payload);
            } else if (mqttClientManager.isStatusTopic(topic)) {
                handleHeartbeat(topic, payload);
            } else {
                log.warn("未知的MQTT主题: {}", topic);
            }
        } catch (Exception e) {
            log.error("处理MQTT消息失败: {}", e.getMessage(), e);
        }
    }

    private void handleSensorData(String topic, String payload) throws JsonProcessingException {
        String deviceId = mqttClientManager.extractDeviceIdFromTopic(topic);
        if (deviceId.isEmpty()) {
            log.warn("无法从topic中提取deviceId: {}", topic);
            return;
        }
        JsonNode root = objectMapper.readTree(payload);
        double lightIntensity = root.has("illuminance") ? root.get("illuminance").asDouble()
                : root.get("lightIntensity").asDouble();
        LocalDateTime reportedAt = LocalDateTime.now();
        if (root.has("timestamp") && !root.get("timestamp").isNull()) {
            reportedAt = LocalDateTime.parse(
                    root.get("timestamp").asText().replace("Z", "").replace(" ", "T"));
        }
        sensorDataService.saveAndAutoControl(deviceId, lightIntensity, reportedAt);
    }

    private void handleControlResponse(String topic, String payload) throws JsonProcessingException {
        String deviceId = mqttClientManager.extractDeviceIdFromTopic(topic);
        JsonNode root = objectMapper.readTree(payload);
        String command = root.get("command").asText();
        String result = root.get("result").asText();
        // 如果payload中也携带了deviceId，优先用payload中的
        if (root.has("deviceId") && !root.get("deviceId").isNull()) {
            deviceId = root.get("deviceId").asText();
        }
        controlService.updateControlResult(deviceId, command, result);

        // ★ 修复: 推送控制结果到前端 WebSocket，避免前端依赖乐观更新+轮询
        webSocketHandler.pushControlResult(deviceId, command, result);
        log.info("已推送控制结果到WebSocket: deviceId={}, command={}, result={}", deviceId, command, result);
    }

    private void handleHeartbeat(String topic, String payload) throws JsonProcessingException {
        String deviceId = mqttClientManager.extractDeviceIdFromTopic(topic);
        if (deviceId.isEmpty()) {
            log.warn("无法从topic中提取deviceId: {}", topic);
            return;
        }
        log.info("收到设备心跳 - deviceId: {}", deviceId);
        deviceService.updateHeartbeat(deviceId);
    }

    @Override
    public void deliveryComplete(IMqttToken token) {
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        log.info("MQTT连接完成: reconnect={}, server={}", reconnect, serverURI);
        if (reconnect) {
            mqttClientManager.subscribeAllDevices();
            log.info("MQTT重连后已重新订阅所有设备主题");
        }
    }

    @Override
    public void authPacketArrived(int reasonCode, MqttProperties properties) {
    }
}
