package com.streetlight.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.messaging.MessageChannel;

@Configuration
@Slf4j
public class MqttConfig {

    @Value("${mqtt.broker}")
    private String broker;

    @Value("${mqtt.client-id}")
    private String clientId;

    @Value("${mqtt.username:}")
    private String username;

    @Value("${mqtt.password:}")
    private String password;

    @Value("${mqtt.topic-prefix:streetlight}")
    private String topicPrefix;

    @Value("${mqtt.connection-timeout:30}")
    private int connectionTimeout;

    @Value("${mqtt.keep-alive-interval:60}")
    private int keepAliveInterval;

    // ============ 连接工厂 ============

    @Bean
    public MqttPahoClientFactory mqttPahoClientFactory() {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(new String[]{broker});
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setConnectionTimeout(connectionTimeout);
        options.setKeepAliveInterval(keepAliveInterval);
        if (!username.isBlank()) {
            options.setUserName(username);
        }
        if (!password.isBlank()) {
            options.setPassword(password.toCharArray());
        }
        factory.setConnectionOptions(options);
        return factory;
    }

    // ============ 入站：消息驱动通道适配器（消费者） ============

    @Bean
    public MessageChannel mqttInputChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageProducer inboundMqttAdapter(MqttPahoClientFactory factory) {
        String adapterClientId = clientId + "-inbound";
        MqttPahoMessageDrivenChannelAdapter adapter =
                new MqttPahoMessageDrivenChannelAdapter(adapterClientId, factory,
                        topicPrefix + "/+/sensor/data",
                        topicPrefix + "/+/status",
                        topicPrefix + "/+/control/response");

        adapter.setCompletionTimeout(5000);
        adapter.setQos(1);
        adapter.setOutputChannel(mqttInputChannel());
        adapter.setConverter(new DefaultPahoMessageConverter());
        return adapter;
    }

    // ============ 出站：用于发送控制指令（发布者） ============

    @Bean
    @ServiceActivator(inputChannel = "mqttOutputChannel")
    public MqttPahoMessageHandler outboundMqttHandler(MqttPahoClientFactory factory) {
        String handlerClientId = clientId + "-outbound";
        MqttPahoMessageHandler handler = new MqttPahoMessageHandler(handlerClientId, factory);
        handler.setAsync(true);
        handler.setDefaultTopic(topicPrefix);
        return handler;
    }

    @Bean
    public MessageChannel mqttOutputChannel() {
        return new DirectChannel();
    }

    // ============ 保留原生 MqttClient（兼容 MqttClientManager 发布逻辑） ============

    @Bean
    public MqttClient mqttClient() {
        try {
            MqttClient client = new MqttClient(broker, clientId, new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(connectionTimeout);
            options.setKeepAliveInterval(keepAliveInterval);
            if (!username.isBlank()) {
                options.setUserName(username);
            }
            if (!password.isBlank()) {
                options.setPassword(password.toCharArray());
            }
            client.connect(options);
            log.info("MQTT客户端已连接: broker={}, clientId={}", broker, clientId);
            return client;
        } catch (Exception e) {
            log.warn("MQTT客户端连接失败(不影响启动): {}，应用将继续运行，MQTT将在Broker可用时自动重连", e.getMessage());
            // 返回一个未连接的客户端实例，后台自动重连
            try {
                MqttClient client = new MqttClient(broker, clientId, new MemoryPersistence());
                return client;
            } catch (Exception ex) {
                log.error("MQTT客户端创建彻底失败", ex);
                return null;
            }
        }
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
