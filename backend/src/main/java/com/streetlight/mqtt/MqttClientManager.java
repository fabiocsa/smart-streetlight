package com.streetlight.mqtt;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MQTT 消息发布管理器
 * <p>
 * 负责向设备发送控制指令。消息消费由 {@link MqttPahoMessageDrivenChannelAdapter} 处理，
 * 此类仅保留发布功能。
 */
@Component
@Slf4j
public class MqttClientManager {

    private final MqttClient mqttClient;

    @Value("${mqtt.topic-prefix:streetlight}")
    private String topicPrefix;

    @Autowired(required = false)
    public MqttClientManager(MqttClient mqttClient) {
        this.mqttClient = mqttClient;
        if (mqttClient == null) {
            log.warn("MQTT客户端未就绪，发布功能将在MQTT连接后可用");
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
        if (mqttClient == null || !mqttClient.isConnected()) {
            log.warn("MQTT客户端未连接，无法发布消息 - topic: {}", topic);
            return;
        }
        try {
            mqttClient.publish(topic, payload.getBytes(), 1, false);
            log.info("MQTT发布 - topic: {}, payload: {}", topic, payload);
        } catch (MqttException e) {
            log.error("MQTT发布失败 - topic: {}: {}", topic, e.getMessage(), e);
        }
    }
}
