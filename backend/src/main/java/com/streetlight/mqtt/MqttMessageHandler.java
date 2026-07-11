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

import java.nio.charset.StandardCharsets;
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
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        log.warn("★★★ 收到MQTT消息 - topic: {}, payload: {}", topic, payload);
        try {
            if (mqttClientManager.isSensorDataTopic(topic)) {
                handleSensorData(topic, payload);
            } else if (mqttClientManager.isCmdResponseTopic(topic)) {
                handleCmdResponse(topic, payload);
            } else if (mqttClientManager.isStatusTopic(topic)) {
                handleHeartbeat(topic, payload);
            } else if (mqttClientManager.isSensorRegisterTopic(topic)) {
                handleSensorRegistration(payload);
            } else if (mqttClientManager.isSensorUnregisterTopic(topic)) {
                handleSensorUnregistration(payload);
            } else {
                log.warn("未知的MQTT主题: {}", topic);
            }
        } catch (Exception e) {
            log.error("处理MQTT消息失败: {}", e.getMessage(), e);
        }
    }

    private void handleSensorData(String topic, String payload) throws JsonProcessingException {
        log.warn("★★★ [MQTT诊断] handleSensorData 被调用: topic={}", topic);
        String sensorIdStr = mqttClientManager.extractSensorIdFromTopic(topic);
        if (sensorIdStr.isEmpty()) {
            log.warn("★★★ [MQTT诊断] 无法从 topic 中提取 sensorId: {}", topic);
            return;
        }
        Long sensorId = Long.parseLong(sensorIdStr);

        JsonNode root = objectMapper.readTree(payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = objectMapper.convertValue(root, LinkedHashMap.class);

        String sensorType = root.has("sensorType") && !root.get("sensorType").isNull()
                ? root.get("sensorType").asText() : "light";

        String deviceId = deviceService.resolveDeviceIdForSensor(sensorId);
        log.warn("★★★ [MQTT诊断] deviceId={}, sensorId={}, sensorType={}, dataKeys={}",
                deviceId, sensorId, sensorType, data.keySet());

        LocalDateTime reportedAt = LocalDateTime.now();
        if (root.has("timestamp") && !root.get("timestamp").isNull()) {
            try {
                String raw = root.get("timestamp").asText().trim();
                // 模拟器发送的是 UTC 时间（带 Z 后缀），DB 存北京时间 → 需要 +8h
                if (raw.endsWith("Z") || raw.contains("+00:00")) {
                    String ts = raw.replace("Z", "").replace("+00:00", "").replace(" ", "T");
                    reportedAt = LocalDateTime.parse(ts).plusHours(8);
                } else if (raw.contains("+08:00") || raw.endsWith("+08")) {
                    String ts = raw.replace("+08:00", "").replace("+08", "").replace(" ", "T");
                    reportedAt = LocalDateTime.parse(ts);
                } else {
                    String ts = raw.replace(" ", "T");
                    reportedAt = LocalDateTime.parse(ts);
                }
            } catch (Exception e) { log.debug("时间戳解析失败: {}", e.getMessage()); }
        }
        sensorDataService.saveAndAutoControl(deviceId, sensorId, sensorType, data, reportedAt);
    }

    /** 处理传感器指令响应（v3: topic = streetlight/sensor/{sensorId}/cmd/response） */
    private void handleCmdResponse(String topic, String payload) throws JsonProcessingException {
        String sensorIdStr = mqttClientManager.extractSensorIdFromTopic(topic);
        if (sensorIdStr.isEmpty()) {
            log.warn("无法从 cmd response topic 中提取 sensorId: {}", topic);
            return;
        }
        Long sensorId = Long.parseLong(sensorIdStr);
        String deviceId = deviceService.resolveDeviceIdForSensor(sensorId);

        JsonNode root = objectMapper.readTree(payload);
        String command = root.has("command") ? root.get("command").asText() : "";
        String result = root.has("result") ? root.get("result").asText() : "fail";

        controlService.updateControlResult(deviceId, command, result);
        webSocketHandler.pushControlResult(deviceId, command, result);
    }

    /** 处理传感器心跳（v2: topic = streetlight/sensor/{sensorId}/status） */
    private void handleHeartbeat(String topic, String payload) {
        String sensorIdStr = mqttClientManager.extractSensorIdFromTopic(topic);
        if (!sensorIdStr.isEmpty()) {
            try {
                Long sensorId = Long.parseLong(sensorIdStr);
                String deviceId = deviceService.resolveDeviceIdForSensor(sensorId);
                if (!deviceId.startsWith("sensor_")) {
                    deviceService.updateHeartbeat(deviceId);
                }
            } catch (NumberFormatException e) {
                log.debug("无法解析 sensorId: {}", sensorIdStr);
            }
        }
    }

    // ============ 传感器注册/注销（v2: 全局 topic，不携带 deviceId） ============

    private void handleSensorRegistration(String payload) {
        log.info("收到传感器注册（全局）");
        try {
            JsonNode root = objectMapper.readTree(payload);
            sensorRegistrationService.handleSensorRegister(root);
        } catch (Exception e) {
            log.error("解析传感器注册消息失败: {}", e.getMessage());
        }
    }

    private void handleSensorUnregistration(String payload) {
        log.info("收到传感器注销（全局）");
        try {
            JsonNode root = objectMapper.readTree(payload);
            Long sensorId = root.has("sensorId") ? root.get("sensorId").asLong() : null;
            if (sensorId != null) {
                sensorRegistrationService.handleSensorUnregister(sensorId);
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
        mqttClientManager.subscribeGlobalTopics();  // subscribeGlobalTopics 内部检查 ingestionEnabled
    }
    @Override
    public void authPacketArrived(int reasonCode, MqttProperties properties) {}
}
