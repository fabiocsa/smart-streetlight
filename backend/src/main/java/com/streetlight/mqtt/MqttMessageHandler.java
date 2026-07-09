package com.streetlight.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streetlight.service.ControlService;
import com.streetlight.service.DeviceRegistrationService;
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
    private final DeviceRegistrationService registrationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MqttMessageHandler(SensorDataService sensorDataService,
                               ControlService controlService,
                               @Lazy DeviceService deviceService,
                               @Lazy MqttClientManager mqttClientManager,
                               WebSocketHandler webSocketHandler,
                               DeviceRegistrationService registrationService) {
        this.sensorDataService = sensorDataService;
        this.controlService = controlService;
        this.deviceService = deviceService;
        this.mqttClientManager = mqttClientManager;
        this.webSocketHandler = webSocketHandler;
        this.registrationService = registrationService;
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
            } else if (mqttClientManager.isRegistrationTopic(topic)) {
                handleDeviceRegistration(topic, payload);
            } else if (mqttClientManager.isDeregistrationTopic(topic)) {
                handleDeviceDeregistration(topic, payload);
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

    /**
     * 处理传感器数据上报。
     * 完整解析 JSON payload 中的所有字段，存储到 data_json 列。
     */
    private void handleSensorData(String topic, String payload) throws JsonProcessingException {
        String deviceId = mqttClientManager.extractDeviceIdFromTopic(topic);
        if (deviceId.isEmpty()) {
            log.warn("无法从topic中提取deviceId: {}", topic);
            return;
        }

        JsonNode root = objectMapper.readTree(payload);

        // 将整个 payload 转为 Map（保留所有字段）
        @SuppressWarnings("unchecked")
        Map<String, Object> data = objectMapper.convertValue(root, LinkedHashMap.class);

        // 提取传感器元信息
        String sensorType = "light";
        if (root.has("sensorType") && !root.get("sensorType").isNull()) {
            sensorType = root.get("sensorType").asText();
        }
        Long sensorId = null;
        if (root.has("sensorId") && !root.get("sensorId").isNull()) {
            sensorId = root.get("sensorId").asLong();
        }

        // 解析时间戳
        LocalDateTime reportedAt = LocalDateTime.now();
        if (root.has("timestamp") && !root.get("timestamp").isNull()) {
            try {
                String ts = root.get("timestamp").asText().replace("Z", "").replace(" ", "T");
                reportedAt = LocalDateTime.parse(ts);
            } catch (Exception e) {
                log.debug("时间戳解析失败，使用当前时间: {}", e.getMessage());
            }
        }

        sensorDataService.saveAndAutoControl(deviceId, sensorId, sensorType, data, reportedAt);
        log.debug("传感器数据已存储: deviceId={}, sensorType={}, fields={}",
                deviceId, sensorType, data.keySet());
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

    // ============ 设备注册/注销处理 ============

    private void handleDeviceRegistration(String topic, String payload) {
        log.info("收到设备注册消息 - topic: {}", topic);
        registrationService.handleDeviceRegistration(payload);
    }

    private void handleDeviceDeregistration(String topic, String payload) {
        String deviceId = mqttClientManager.extractDeviceIdFromRegistrationTopic(topic);
        log.info("收到设备注销消息 - deviceId: {}", deviceId);
        registrationService.handleDeviceDeregistration(deviceId);
    }

    private void handleSensorRegistration(String topic, String payload) {
        String deviceId = mqttClientManager.extractDeviceIdFromRegistrationTopic(topic);
        log.info("收到传感器注册消息 - deviceId: {}, payload: {}", deviceId, payload);
        try {
            JsonNode root = objectMapper.readTree(payload);
            registrationService.handleSensorRegister(deviceId, root);
        } catch (Exception e) {
            log.error("解析传感器注册消息失败: {}", e.getMessage());
        }
    }

    private void handleSensorUnregistration(String topic, String payload) {
        String deviceId = mqttClientManager.extractDeviceIdFromRegistrationTopic(topic);
        log.info("收到传感器注销消息 - deviceId: {}, payload: {}", deviceId, payload);
        try {
            JsonNode root = objectMapper.readTree(payload);
            Long sensorId = root.has("sensorId") ? root.get("sensorId").asLong() : null;
            if (sensorId != null) {
                registrationService.handleSensorUnregister(deviceId, sensorId);
            }
        } catch (Exception e) {
            log.error("解析传感器注销消息失败: {}", e.getMessage());
        }
    }

    @Override
    public void authPacketArrived(int reasonCode, MqttProperties properties) {
    }
}
