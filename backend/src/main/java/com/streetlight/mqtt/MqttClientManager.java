package com.streetlight.mqtt;

import com.streetlight.service.DeviceService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class MqttClientManager {

    private final MqttClient mqttClient;
    private final MqttMessageHandler messageHandler;

    @Value("${mqtt.topic-prefix:streetlight}")
    private String topicPrefix;

    @PostConstruct
    public void init() {
        try {
            mqttClient.setCallback(messageHandler);

            // 订阅所有设备主题
            String sensorTopic = topicPrefix + "/+/sensor/data";
            String statusTopic = topicPrefix + "/+/status";
            String responseTopic = topicPrefix + "/+/control/response";

            mqttClient.subscribe(sensorTopic, 1);
            mqttClient.subscribe(statusTopic, 1);
            mqttClient.subscribe(responseTopic, 1);

            log.info("MQTT已订阅主题: {}, {}, {}", sensorTopic, statusTopic, responseTopic);
        } catch (MqttException e) {
            log.error("MQTT订阅失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 发送控制指令到设备
     */
    public void publishControl(String deviceId, String command, String source) {
        String topic = topicPrefix + "/" + deviceId + "/control";
        String payload = String.format(
                "{\"command\":\"%s\",\"source\":\"%s\",\"timestamp\":\"%s\"}",
                command, source, java.time.LocalDateTime.now().toString());
        publish(topic, payload);
    }

    private void publish(String topic, String payload) {
        try {
            mqttClient.publish(topic, payload.getBytes(), 1, false);
            log.info("MQTT发布 - topic: {}, payload: {}", topic, payload);
        } catch (MqttException e) {
            log.error("MQTT发布失败 - topic: {}: {}", topic, e.getMessage(), e);
        }
    }
}
