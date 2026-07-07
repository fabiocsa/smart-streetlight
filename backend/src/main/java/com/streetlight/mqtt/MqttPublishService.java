package com.streetlight.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
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
        try {
            String topic = "streetlight/" + deviceId + "/control";
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("command", command);
            payload.put("source", source);
            payload.put("timestamp", LocalDateTime.now().toString());
            String json = objectMapper.writeValueAsString(payload);
            MqttMessage message = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
            message.setQos(1);
            mqttClient.publish(topic, message);
            log.info("MQTT发布指令 - topic: {}, payload: {}", topic, json);
        } catch (MqttException | JsonProcessingException e) {
            log.error("MQTT发布指令失败 - deviceId: {}: {}", deviceId, e.getMessage(), e);
        }
    }
}
