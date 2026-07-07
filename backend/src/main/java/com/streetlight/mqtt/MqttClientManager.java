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

    @PostConstruct
    public void init() {
        // 先设置回调（即使未连接，注册回调本身是安全的）
        mqttClient.setCallback(messageHandler);
        log.info("MQTT 回调已注册");
    }

    /**
     * 应用完全就绪后连接 MQTT Broker 并订阅设备主题
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            connectToBroker();
            subscribeAllDevices();
        } catch (Exception e) {
            log.error("MQTT 初始化失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 连接到 MQTT Broker
     */
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

    /**
     * 订阅指定设备的所有相关主题
     */
    public void subscribeDevice(String deviceId) {
        if (!mqttClient.isConnected()) {
            log.warn("MQTT 未连接，跳过订阅 - deviceId: {}", deviceId);
            return;
        }
        try {
            String sensorTopic = getSensorTopic(deviceId);
            String responseTopic = getControlResponseTopic(deviceId);

            MqttSubscription[] subscriptions = {
                    new MqttSubscription(sensorTopic, 1),
                    new MqttSubscription(responseTopic, 1)
            };
            mqttClient.subscribe(subscriptions);
            log.info("已订阅设备主题 - deviceId: {}, topics: [{}, {}]", deviceId, sensorTopic, responseTopic);
        } catch (MqttException e) {
            log.error("订阅设备主题失败 - deviceId: {}: {}", deviceId, e.getMessage(), e);
        }
    }

    /**
     * 取消订阅指定设备的所有相关主题
     */
    public void unsubscribeDevice(String deviceId) {
        try {
            String sensorTopic = getSensorTopic(deviceId);
            String responseTopic = getControlResponseTopic(deviceId);

            String[] topics = {sensorTopic, responseTopic};
            mqttClient.unsubscribe(topics);
            log.info("已取消订阅设备主题 - deviceId: {}, topics: [{}, {}]", deviceId, sensorTopic, responseTopic);
        } catch (MqttException e) {
            log.error("取消订阅设备主题失败 - deviceId: {}: {}", deviceId, e.getMessage(), e);
        }
    }

    /**
     * 订阅所有已有设备的主题（启动时/重连时调用）
     */
    public void subscribeAllDevices() {
        if (!mqttClient.isConnected()) {
            log.warn("MQTT 未连接，跳过批量订阅");
            return;
        }
        List<Device> devices = deviceRepository.findAll();
        if (devices.isEmpty()) {
            log.warn("数据库中无设备，跳过MQTT主题订阅");
            return;
        }
        int subscribed = 0;
        for (Device device : devices) {
            // 逐个订阅时也检查连接状态，防止中途断连导致连锁错误
            if (!mqttClient.isConnected()) {
                log.warn("MQTT 连接已断开，停止批量订阅（已订阅 {}/{}）", subscribed, devices.size());
                return;
            }
            subscribeDevice(device.getDeviceId());
            subscribed++;
        }
        log.info("已完成所有设备主题订阅，共 {} 台设备", subscribed);
    }

    /**
     * 获取设备的传感器数据上报主题
     */
    public String getSensorTopic(String deviceId) {
        return topicPrefix + "/" + deviceId + "/sensor/data";
    }

    /**
     * 获取设备的控制响应主题
     */
    public String getControlResponseTopic(String deviceId) {
        return topicPrefix + "/" + deviceId + "/control/response";
    }

    /**
     * 从完整topic路径中提取deviceId
     * 格式: {prefix}/{deviceId}/sensor/data 或 {prefix}/{deviceId}/control/response
     */
    public String extractDeviceIdFromTopic(String topic) {
        String[] parts = topic.split("/");
        if (parts.length >= 2) {
            return parts[1];
        }
        return "";
    }

    /**
     * 判断是否为传感器数据主题
     */
    public boolean isSensorDataTopic(String topic) {
        return topic.endsWith("/sensor/data");
    }

    /**
     * 判断是否为控制响应主题
     */
    public boolean isControlResponseTopic(String topic) {
        return topic.endsWith("/control/response");
    }

    /**
     * 判断是否为设备状态/心跳主题
     */
    public boolean isStatusTopic(String topic) {
        return topic.endsWith("/status");
    }
}
