package com.streetlight.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streetlight.entity.Sensor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class MqttPublishService {

    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void publishCommand(String deviceId, String command, String source) {
        publishCommand(deviceId, command, source, null);
    }

    public void publishCommand(String deviceId, String command, String source, Integer brightness) {
        if (!mqttClient.isConnected()) {
            log.warn("MQTT 未连接，跳过发布指令 - deviceId: {}, command: {}", deviceId, command);
            return;
        }
        try {
            String topic = "streetlight/" + deviceId + "/control";
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("command", command);
            payload.put("source", source);
            payload.put("timestamp", LocalDateTime.now().toString());
            if (brightness != null) {
                payload.put("brightness", brightness);
            }
            String json = objectMapper.writeValueAsString(payload);
            MqttMessage message = new MqttMessage(json.getBytes());
            message.setQos(1);
            mqttClient.publish(topic, message);
            log.info("MQTT发布指令 - topic: {}, payload: {}", topic, json);
        } catch (MqttException | JsonProcessingException e) {
            log.error("MQTT发布指令失败 - deviceId: {}: {}", deviceId, e.getMessage(), e);
        }
    }

    /**
     * 发布传感器配置指令到模拟器
     * 主题: streetlight/mock-sender/config
     */
    public void publishSensorConfig(String deviceId, String action, Sensor sensor) {
        if (!mqttClient.isConnected()) {
            log.warn("MQTT 未连接，跳过发布传感器配置 - deviceId: {}, action: {}", deviceId, action);
            return;
        }
        try {
            String topic = "streetlight/mock-sender/config";
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("action", action);
            payload.put("deviceId", deviceId);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("sensorId", sensor.getId());
            params.put("sensorType", sensor.getSensorType());
            params.put("displayName", sensor.getDisplayName());
            params.put("dataTopic", sensor.getDataTopic());
            params.put("interval", sensor.getReportFrequency());
            params.put("enabled", sensor.getEnabled());
            if (sensor.getConfigJson() != null) {
                params.put("configJson", sensor.getConfigJson());
            }
            payload.put("params", params);
            payload.put("timestamp", LocalDateTime.now().toString());
            String json = objectMapper.writeValueAsString(payload);
            MqttMessage message = new MqttMessage(json.getBytes());
            message.setQos(1);
            mqttClient.publish(topic, message);
            log.info("MQTT发布传感器配置 - topic: {}, action: {}, payload: {}", topic, action, json);
        } catch (MqttException | JsonProcessingException e) {
            log.error("MQTT发布传感器配置失败 - deviceId: {}: {}", deviceId, e.getMessage(), e);
        }
    }
}
