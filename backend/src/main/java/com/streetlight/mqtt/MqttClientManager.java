package com.streetlight.mqtt;

import com.streetlight.entity.Device;
import com.streetlight.repository.DeviceRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class MqttClientManager {

    private final MqttClient mqttClient;
    @Lazy
    private final MqttMessageHandler messageHandler;
    private final DeviceRepository deviceRepository;

    @Value("${mqtt.broker}")
    private String broker;

    @Value("${mqtt.username:}")
    private String username;

    @Value("${mqtt.password:}")
    private String password;

    @Value("${mqtt.connection-timeout:30}")
    private int connectionTimeout;

    @Value("${mqtt.keep-alive-interval:60}")
    private int keepAliveInterval;

    @Value("${mqtt.topic-prefix:streetlight}")
    private String topicPrefix;

    public boolean isConnected() {
        return mqttClient.isConnected();
    }

    @PostConstruct
    public void init() {
        mqttClient.setCallback(messageHandler);
        log.info("MQTT 回调已注册");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            connectToBroker();
            subscribeGlobalTopics();
            subscribeAllDevices();
        } catch (Exception e) {
            log.error("MQTT 初始化失败: {}", e.getMessage(), e);
        }
    }

    private void connectToBroker() {
        try {
            MqttConnectionOptions options = new MqttConnectionOptions();
            options.setAutomaticReconnect(true);
            options.setCleanStart(true);
            options.setConnectionTimeout(connectionTimeout);
            options.setKeepAliveInterval(keepAliveInterval);

            if (!username.isBlank()) {
                options.setUserName(username);
            }
            if (!password.isBlank()) {
                options.setPassword(password.getBytes());
            }

            mqttClient.connect(options);
            log.info("MQTT 已连接到 Broker: {}", broker);
        } catch (MqttException e) {
            log.error("MQTT 连接失败: {}", e.getMessage());
        }
    }

    // ======================== 主题订阅 ========================

    /** 订阅指定设备的控制相关主题 */
    public void subscribeDevice(String deviceId) {
        if (!mqttClient.isConnected()) {
            log.warn("MQTT 未连接，跳过订阅 - deviceId: {}", deviceId);
            return;
        }
        try {
            String responseTopic = getControlResponseTopic(deviceId);
            MqttSubscription[] subscriptions = {
                    new MqttSubscription(responseTopic, 1)
            };
            mqttClient.subscribe(subscriptions);
            log.info("已订阅设备控制响应主题 - deviceId: {}, topic: {}", deviceId, responseTopic);
        } catch (MqttException e) {
            log.error("订阅设备主题失败 - deviceId: {}: {}", deviceId, e.getMessage(), e);
        }
    }

    /** 取消订阅指定设备 */
    public void unsubscribeDevice(String deviceId) {
        try {
            String responseTopic = getControlResponseTopic(deviceId);
            mqttClient.unsubscribe(new String[]{responseTopic});
            log.info("已取消订阅设备主题 - deviceId: {}", deviceId);
        } catch (MqttException e) {
            log.error("取消订阅设备主题失败 - deviceId: {}: {}", deviceId, e.getMessage(), e);
        }
    }

    /** 订阅所有设备的控制主题（启动时/重连时） */
    public void subscribeAllDevices() {
        if (!mqttClient.isConnected()) {
            log.warn("MQTT 未连接，跳过批量订阅");
            return;
        }
        List<Device> devices = deviceRepository.findAll();
        if (devices.isEmpty()) {
            log.warn("数据库中无设备，跳过 MQTT 主题订阅");
            return;
        }
        int subscribed = 0;
        for (Device device : devices) {
            if (!mqttClient.isConnected()) {
                log.warn("MQTT 连接已断开，停止批量订阅（已订阅 {}/{}）", subscribed, devices.size());
                return;
            }
            subscribeDevice(device.getDeviceId());
            subscribed++;
        }
        log.info("已完成所有设备主题订阅，共 {} 台设备", subscribed);
    }

    /** 订阅全局传感器主题（注册/注销/数据/状态） */
    public void subscribeGlobalTopics() {
        if (!mqttClient.isConnected()) {
            log.warn("MQTT 未连接，跳过全局传感器主题订阅");
            return;
        }
        try {
            String sensorRegisterTopic = topicPrefix + "/sensor/register";
            String sensorUnregisterTopic = topicPrefix + "/sensor/unregister";
            String sensorDataTopic = topicPrefix + "/sensor/+/data";
            String sensorStatusTopic = topicPrefix + "/sensor/+/status";

            MqttSubscription[] subscriptions = {
                    new MqttSubscription(sensorRegisterTopic, 1),
                    new MqttSubscription(sensorUnregisterTopic, 1),
                    new MqttSubscription(sensorDataTopic, 1),
                    new MqttSubscription(sensorStatusTopic, 1),
            };
            mqttClient.subscribe(subscriptions);
            log.info("已订阅全局传感器主题: [{}, {}, {}, {}]",
                    sensorRegisterTopic, sensorUnregisterTopic, sensorDataTopic, sensorStatusTopic);
        } catch (MqttException e) {
            log.error("订阅全局传感器主题失败: {}", e.getMessage(), e);
        }
    }

    // ======================== 主题生成 ========================

    /** 传感器数据上报主题: streetlight/sensor/{sensorId}/data */
    public String getSensorTopic(String sensorId) {
        return topicPrefix + "/sensor/" + sensorId + "/data";
    }

    /** 传感器心跳主题: streetlight/sensor/{sensorId}/status */
    public String getSensorStatusTopic(String sensorId) {
        return topicPrefix + "/sensor/" + sensorId + "/status";
    }

    /** 控制响应主题: streetlight/{deviceId}/control/response */
    public String getControlResponseTopic(String deviceId) {
        return topicPrefix + "/" + deviceId + "/control/response";
    }

    // ======================== 主题判断 ========================

    /** 判断是否为传感器数据主题: streetlight/sensor/{id}/data */
    public boolean isSensorDataTopic(String topic) {
        return topic.matches(topicPrefix + "/sensor/[^/]+/data");
    }

    /** 判断是否为控制响应主题: streetlight/{deviceId}/control/response */
    public boolean isControlResponseTopic(String topic) {
        return topic.endsWith("/control/response");
    }

    /** 判断是否为传感器心跳主题: streetlight/sensor/{id}/status */
    public boolean isStatusTopic(String topic) {
        return topic.matches(topicPrefix + "/sensor/[^/]+/status");
    }

    /** 判断是否为传感器注册主题: streetlight/sensor/register */
    public boolean isSensorRegisterTopic(String topic) {
        return topic.equals(topicPrefix + "/sensor/register");
    }

    /** 判断是否为传感器注销主题: streetlight/sensor/unregister */
    public boolean isSensorUnregisterTopic(String topic) {
        return topic.equals(topicPrefix + "/sensor/unregister");
    }

    // ======================== ID 提取 ========================

    /** 从传感器主题中提取 sensorId: streetlight/sensor/{sensorId}/... → sensorId */
    public String extractSensorIdFromTopic(String topic) {
        String[] parts = topic.split("/");
        // format: {prefix}/sensor/{sensorId}/data or /status
        if (parts.length >= 3 && "sensor".equals(parts[1])) {
            return parts[2];
        }
        return "";
    }

    /** 从控制响应主题中提取 deviceId: streetlight/{deviceId}/control/response → deviceId */
    public String extractDeviceIdFromTopic(String topic) {
        String[] parts = topic.split("/");
        if (parts.length >= 2) {
            return parts[1];
        }
        return "";
    }

    /** 从旧格式注册主题提取 deviceId（已废弃，保留兼容） */
    public String extractDeviceIdFromRegistrationTopic(String topic) {
        String[] parts = topic.split("/");
        if (parts.length >= 2) {
            return parts[1];
        }
        return "";
    }
}
