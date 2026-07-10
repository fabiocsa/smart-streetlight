package com.streetlight.mqtt;

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

@Component
@Slf4j
@RequiredArgsConstructor
public class MqttClientManager {

    private final MqttClient mqttClient;
    @Lazy
    private final MqttMessageHandler messageHandler;

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

    /** 订阅全局传感器主题（使用 EMQX 共享订阅防止多实例重复消费）。
     *  $share/backend/topic 格式 — EMQX 将每条消息仅投递给组内一个订阅者。
     *  消息到达时 topic 为原始 topic（不含 $share 前缀），不影响 isXxxTopic() 匹配。 */
    public void subscribeGlobalTopics() {
        if (!mqttClient.isConnected()) {
            log.warn("MQTT 未连接，跳过全局传感器主题订阅");
            return;
        }
        try {
            // ★ 共享订阅前缀：多实例部署时每条消息只被一个实例处理
            String share = "$share/backend/";

            String sensorRegisterTopic = share + topicPrefix + "/sensor/register";
            String sensorUnregisterTopic = share + topicPrefix + "/sensor/unregister";
            String sensorDataTopic = share + topicPrefix + "/sensor/+/data";
            String sensorStatusTopic = share + topicPrefix + "/sensor/+/status";
            String sensorCmdResponseTopic = share + topicPrefix + "/sensor/+/cmd/response";

            MqttSubscription[] subscriptions = {
                    new MqttSubscription(sensorRegisterTopic, 1),
                    new MqttSubscription(sensorUnregisterTopic, 1),
                    new MqttSubscription(sensorDataTopic, 1),
                    new MqttSubscription(sensorStatusTopic, 1),
                    new MqttSubscription(sensorCmdResponseTopic, 1),
            };
            mqttClient.subscribe(subscriptions);
            log.info("已订阅全局传感器主题（共享订阅模式，防多实例重复）: [{}, {}, {}, {}, {}]",
                    sensorRegisterTopic, sensorUnregisterTopic,
                    sensorDataTopic, sensorStatusTopic, sensorCmdResponseTopic);
        } catch (MqttException e) {
            log.error("订阅全局传感器主题失败: {}", e.getMessage(), e);
        }
    }

    // ======================== 主题生成 ========================

    /** 传感器控制指令下行主题: streetlight/sensor/{sensorId}/cmd */
    public String getSensorCmdTopic(Long sensorId) {
        return topicPrefix + "/sensor/" + sensorId + "/cmd";
    }

    /** 传感器控制响应上行主题: streetlight/sensor/{sensorId}/cmd/response */
    public String getSensorCmdResponseTopic(Long sensorId) {
        return topicPrefix + "/sensor/" + sensorId + "/cmd/response";
    }

    // ======================== 主题判断 ========================

    /** 判断是否为传感器数据主题: streetlight/sensor/{id}/data */
    public boolean isSensorDataTopic(String topic) {
        return topic.matches(topicPrefix + "/sensor/[^/]+/data");
    }

    /** 判断是否为传感器指令响应主题: streetlight/sensor/{sensorId}/cmd/response */
    public boolean isCmdResponseTopic(String topic) {
        return topic.matches(topicPrefix + "/sensor/[^/]+/cmd/response");
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
        // format: {prefix}/sensor/{sensorId}/data|status|cmd|cmd/response
        if (parts.length >= 3 && "sensor".equals(parts[1])) {
            return parts[2];
        }
        return "";
    }
}
