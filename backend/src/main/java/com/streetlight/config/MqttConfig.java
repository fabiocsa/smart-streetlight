package com.streetlight.config;

import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
        MqttClient client = new MqttClient(broker, clientId, new MemoryPersistence());
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

        client.connect(options);
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
