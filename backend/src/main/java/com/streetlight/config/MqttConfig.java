package com.streetlight.config;

import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

@Configuration
public class MqttConfig {

    @Value("${mqtt.broker}")
    private String broker;

    @Value("${mqtt.client-id}")
    private String clientId;

    @Value("${mqtt.username:}")
    private String username;

    @Value("${mqtt.password:}")
    private String password;

    @Value("${mqtt.connection-timeout:30}")
    private int connectionTimeout;

    @Value("${mqtt.keep-alive-interval:60}")
    private int keepAliveInterval;

    @Bean
    public MqttClient mqttClient() throws MqttException {
        // 添加随机后缀避免多实例运行时 clientId 冲突导致 Session Taken Over
        String uniqueClientId = clientId + "-" + UUID.randomUUID().toString().substring(0, 8);
        MqttClient client = new MqttClient(broker, uniqueClientId, new MemoryPersistence());
        // 注意：不在这里连接，由 MqttClientManager 在应用就绪后连接
        return client;
    }

    @Bean
    public MqttConnectionOptions mqttConnectOptions() {
        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setAutomaticReconnect(true);
        options.setCleanStart(true);
        options.setConnectionTimeout(connectionTimeout);
        options.setKeepAliveInterval(keepAliveInterval);
        return options;
    }
}
