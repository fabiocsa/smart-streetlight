package com.streetlight.mqtt;

import com.streetlight.entity.SensorData;
import com.streetlight.service.ControlService;
import com.streetlight.service.DeviceService;
import com.streetlight.service.SensorDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class MqttMessageHandler implements MqttCallback {

    private final DeviceService deviceService;
    private final SensorDataService sensorDataService;
    private final ControlService controlService;

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT连接断开: {}", cause.getMessage());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String payload = new String(message.getPayload());
        log.info("收到MQTT消息 - topic: {}, payload: {}", topic, payload);

        try {
            if (topic.contains("/sensor/data")) {
                handleSensorData(payload);
            } else if (topic.contains("/status")) {
                handleStatus(payload);
            } else if (topic.contains("/control/response")) {
                handleControlResponse(payload);
            }
        } catch (Exception e) {
            log.error("处理MQTT消息失败: {}", e.getMessage(), e);
        }
    }

    private void handleSensorData(String payload) {
        // TODO: 解析JSON -> 保存SensorData -> 触发联动控制
        // 示例格式: {"deviceId":"SL-001","lightIntensity":125.5,"timestamp":"2026-07-01T10:00:00Z"}
        log.info("处理传感器数据: {}", payload);
    }

    private void handleStatus(String payload) {
        // TODO: 解析JSON -> 更新心跳 -> 检查在线状态
        // 示例格式: {"deviceId":"SL-001","status":"online","battery":85}
        log.info("处理设备状态: {}", payload);
    }

    private void handleControlResponse(String payload) {
        // TODO: 解析JSON -> 记录控制结果
        // 示例格式: {"command":"on","result":"success","timestamp":"2026-07-01T10:00:01Z"}
        log.info("处理控制响应: {}", payload);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // 消息送达回调
    }
}
