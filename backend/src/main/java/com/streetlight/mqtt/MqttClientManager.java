package com.streetlight.mqtt;

import com.streetlight.service.DeviceService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
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
            String sensorTopic = topicPrefix + "/+/sensor/data";
            String responseTopic = topicPrefix + "/+/control/response";
            mqttClient.subscribe(sensorTopic, 1);
            mqttClient.subscribe(responseTopic, 1);
            log.info("MQTT已订阅主题: {}, {}", sensorTopic, responseTopic);
        } catch (MqttException e) {
            log.error("MQTT订阅失败: {}", e.getMessage(), e);
        }
    }
}
