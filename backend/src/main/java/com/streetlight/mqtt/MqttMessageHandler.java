package com.streetlight.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streetlight.service.ControlService;
import com.streetlight.service.DeviceService;
import com.streetlight.service.SensorDataService;
import com.streetlight.service.SensorRegistrationService;
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
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Slf4j
public class MqttMessageHandler implements MqttCallback {

    private final SensorDataService sensorDataService;
    private final ControlService controlService;
    private final DeviceService deviceService;
    private final MqttClientManager mqttClientManager;
    private final WebSocketHandler webSocketHandler;
    private final SensorRegistrationService sensorRegistrationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MqttMessageHandler(SensorDataService sensorDataService,
                               ControlService controlService,
                               @Lazy DeviceService deviceService,
                               @Lazy MqttClientManager mqttClientManager,
                               WebSocketHandler webSocketHandler,
                               SensorRegistrationService sensorRegistrationService) {
        this.sensorDataService = sensorDataService;
        this.controlService = controlService;
        this.deviceService = deviceService;
        this.mqttClientManager = mqttClientManager;
        this.webSocketHandler = webSocketHandler;
        this.sensorRegistrationService = sensorRegistrationService;
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
            } else if (mqttClientManager.isSensorRegisterTopic(topic)) {
                handleSensorRegistration(topic, payload);
            } else if (mqttClientManager.isSensorUnregisterTopic(topic)) {
                handleSensorUnregistration(topic, payload);
            } else {
                log.warn("未知的MQTT主题: {}", topic);
            }
        } catch (Exception e) {
            log.error("处理MQTT消息失败: {}", e.getMessage(), e);
        }
    }

    /** 处理传感器数据上报：完整 JSON → data_json 列 */
    private void handleSensorData(String topic, String payload) throws JsonProcessingException {
        String deviceId = mqttClientManager.extractDeviceIdFromTopic(topic);
        if (deviceId.isEmpty()) {
            log.warn("无法从topic中提取deviceId: {}", topic);
            return;
        }
        JsonNode root = objectMapper.readTree(payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = objectMapper.convertValue(root, LinkedHashMap.class);

        String sensorType = root.has("sensorType") && !root.get("sensorType").isNull()
                ? root.get("sensorType").asText() : "light";
        Long sensorId = root.has("sensorId") && !root.get("sensorId").isNull()
                ? root.get("sensorId").asLong() : null;

        LocalDateTime reportedAt = LocalDateTime.now();
        if (root.has("timestamp") && !root.get("timestamp").isNull()) {
            try {
                String ts = root.get("timestamp").asText().replace("Z", "").replace(" ", "T");
                reportedAt = LocalDateTime.parse(ts);
            } catch (Exception e) { log.debug("时间戳解析失败: {}", e.getMessage()); }
        }
        sensorDataService.saveAndAutoControl(deviceId, sensorId, sensorType, data, reportedAt);
    }

    private void handleControlResponse(String topic, String payload) throws JsonProcessingException {
        String deviceId = mqttClientManager.extractDeviceIdFromTopic(topic);
        JsonNode root = objectMapper.readTree(payload);
        String command = root.get("command").asText();
        String result = root.get("result").asText();
        if (root.has("deviceId") && !root.get("deviceId").isNull()) {
            deviceId = root.get("deviceId").asText();
        }
        controlService.updateControlResult(deviceId, command, result);
        webSocketHandler.pushControlResult(deviceId, command, result);
    }

    private void handleHeartbeat(String topic, String payload) {
        String deviceId = mqttClientManager.extractDeviceIdFromTopic(topic);
        if (!deviceId.isEmpty()) {
            deviceService.updateHeartbeat(deviceId);
        }
    }

    // ============ 传感器注册/注销（仅传感器，不涉及设备） ============

    private void handleSensorRegistration(String topic, String payload) {
        String deviceId = mqttClientManager.extractDeviceIdFromRegistrationTopic(topic);
        log.info("收到传感器注册 - deviceId={}", deviceId);
        try {
            JsonNode root = objectMapper.readTree(payload);
            sensorRegistrationService.handleSensorRegister(deviceId, root);
        } catch (Exception e) {
            log.error("解析传感器注册消息失败: {}", e.getMessage());
        }
    }

    private void handleSensorUnregistration(String topic, String payload) {
        String deviceId = mqttClientManager.extractDeviceIdFromRegistrationTopic(topic);
        log.info("收到传感器注销 - deviceId={}", deviceId);
        try {
            JsonNode root = objectMapper.readTree(payload);
            Long sensorId = root.has("sensorId") ? root.get("sensorId").asLong() : null;
            if (sensorId != null) {
                sensorRegistrationService.handleSensorUnregister(deviceId, sensorId);
            }
        } catch (Exception e) {
            log.error("解析传感器注销消息失败: {}", e.getMessage());
        }
    }

    @Override
    public void deliveryComplete(IMqttToken token) {}
    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        log.info("MQTT连接完成: reconnect={}, server={}", reconnect, serverURI);
        if (reconnect) {
            mqttClientManager.subscribeAllDevices();
            log.info("MQTT重连后已重新订阅所有设备主题");
        }
    }
    @Override
    public void authPacketArrived(int reasonCode, MqttProperties properties) {}
}
