package com.streetlight.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streetlight.service.ControlService;
import com.streetlight.service.SensorDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@Slf4j
@RequiredArgsConstructor
public class MqttMessageHandler implements MqttCallback {

    private final SensorDataService sensorDataService;
    private final ControlService controlService;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
            if (topic.contains("/sensor/data")) {
                handleSensorData(payload);
            } else if (topic.contains("/control/response")) {
                handleControlResponse(payload);
            }
        } catch (Exception e) {
            log.error("处理MQTT消息失败: {}", e.getMessage(), e);
        }
    }

    private void handleSensorData(String payload) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(payload);
        String deviceId = root.get("deviceId").asText();
        double lightIntensity = root.get("lightIntensity").asDouble();
        LocalDateTime reportedAt = LocalDateTime.now();
        if (root.has("timestamp") && !root.get("timestamp").isNull()) {
            reportedAt = LocalDateTime.parse(root.get("timestamp").asText().replace("Z", ""));
        }
        sensorDataService.saveAndAutoControl(deviceId, lightIntensity, reportedAt);
    }

    private void handleControlResponse(String payload) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(payload);
        String command = root.get("command").asText();
        String result = root.get("result").asText();
        String deviceId = root.has("deviceId") ? root.get("deviceId").asText() : "";
        controlService.updateControlResult(deviceId, command, result);
    }

    @Override
    public void deliveryComplete(IMqttToken token) {
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        log.info("MQTT连接完成: reconnect={}, server={}", reconnect, serverURI);
    }

    @Override
    public void authPacketArrived(int reasonCode, MqttProperties properties) {
    }
}
