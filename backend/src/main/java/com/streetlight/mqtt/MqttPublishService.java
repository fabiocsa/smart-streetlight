package com.streetlight.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MQTT 发布服务（v3）
 * - 控制指令发布到传感器 cmd 主题（不再使用设备级主题）
 * - 不依赖 MqttClientManager（避免循环依赖）
 */
@Service
@Slf4j
public class MqttPublishService {

    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MqttPublishService(MqttClient mqttClient) {
        this.mqttClient = mqttClient;
    }

    /**
     * 发布控制指令到传感器 cmd 主题。
     * @param sensorId  模拟器内部传感器ID（simulator_sensor_id）
     * @param deviceId  目标设备 business key（记录到 payload）
     * @param command   on / off
     * @param source    auto / manual
     */
    public void publishCommand(Long sensorId, String deviceId, String command, String source) {
        publishCommand(sensorId, deviceId, command, source, null);
    }

    /**
     * 发布控制指令（含亮度）到传感器 cmd 主题。
     * 主题: streetlight/sensor/{sensorId}/cmd
     */
    public void publishCommand(Long sensorId, String deviceId, String command,
                               String source, Integer brightness) {
        if (!mqttClient.isConnected()) {
            log.warn("MQTT 未连接，跳过发布指令 - sensorId: {}, command: {}", sensorId, command);
            return;
        }
        try {
            // 直接构造 topic，不依赖 MqttClientManager（避免循环依赖）
            String topic = "streetlight/sensor/" + sensorId + "/cmd";
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("command", command);
            payload.put("source", source);
            payload.put("deviceId", deviceId);
            payload.put("timestamp", LocalDateTime.now().toString());
            if (brightness != null) {
                payload.put("brightness", brightness);
            }
            String json = objectMapper.writeValueAsString(payload);
            MqttMessage message = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
            message.setQos(1);
            mqttClient.publish(topic, message);
            log.info("MQTT发布指令 - topic: {}, payload: {}", topic, json);
        } catch (MqttException | JsonProcessingException e) {
            log.error("MQTT发布指令失败 - sensorId: {}: {}", sensorId, e.getMessage(), e);
        }
    }
}
