package com.streetlight.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streetlight.entity.Device;
import com.streetlight.entity.SensorData;
import com.streetlight.service.AlarmService;
import com.streetlight.service.ControlService;
import com.streetlight.service.DeviceService;
import com.streetlight.service.SensorDataService;
import com.streetlight.websocket.WebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Component
@Slf4j
@RequiredArgsConstructor
public class MqttMessageHandler implements MqttCallback {

    private final DeviceService deviceService;
    private final SensorDataService sensorDataService;
    private final ControlService controlService;
    private final AlarmService alarmService;
    private final WebSocketHandler webSocketHandler;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
                handleControlResponse(topic, payload);
            }
        } catch (Exception e) {
            log.error("处理MQTT消息失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理传感器数据上报
     * 1. 解析JSON → 保存SensorData
     * 2. 触发光照联动控制（auto模式）
     * 3. WebSocket推送传感器实时数据 + 设备状态变更
     */
    private void handleSensorData(String payload) throws Exception {
        JsonNode json = objectMapper.readTree(payload);
        String deviceId = json.get("deviceId").asText();
        double lightIntensity = json.get("lightIntensity").asDouble();
        String timestampStr = json.get("timestamp").asText();

        // 校验光照强度范围 (0~2000 Lux)
        if (lightIntensity < 0 || lightIntensity > 2000) {
            log.warn("光照强度数据超出范围(0~2000), deviceId={}, value={}", deviceId, lightIntensity);
            // 仍然保存数据用于追溯，不阻断流程
        }

        // 解析时间戳（支持ISO格式带Z后缀）
        LocalDateTime reportedAt = OffsetDateTime.parse(timestampStr, DateTimeFormatter.ISO_DATE_TIME)
                .toLocalDateTime();

        // 1. 保存传感器数据
        SensorData data = SensorData.builder()
                .deviceId(deviceId)
                .lightIntensity(lightIntensity)
                .reportedAt(reportedAt)
                .build();
        sensorDataService.saveSensorData(data);
        log.debug("传感器数据已保存 - deviceId: {}, lightIntensity: {}, reportedAt: {}",
                deviceId, lightIntensity, reportedAt);

        // 2. 触发光照联动控制
        Optional<Device> optDevice = deviceService.getDeviceByDeviceId(deviceId);
        optDevice.ifPresent(device -> {
            String command = controlService.evaluateAutoControl(device, lightIntensity);
            if (command != null) {
                log.info("光照联动触发 - deviceId: {}, command: {}, lightIntensity: {}",
                        deviceId, command, lightIntensity);
                // 联动后重新获取设备最新状态用于推送
                deviceService.getDeviceByDeviceId(deviceId).ifPresent(updatedDevice -> {
                    webSocketHandler.pushDeviceStatus(
                            deviceId, updatedDevice.getStatus(), updatedDevice.getLightStatus());
                });
            }
        });

        // 3. WebSocket推送传感器实时数据
        webSocketHandler.pushSensorData(deviceId, lightIntensity, reportedAt.toString());
    }

    /**
     * 处理设备心跳/状态上报
     * 1. 解析JSON → 更新设备心跳时间
     * 2. 如果设备从离线恢复上线，自动关闭未处理的离线告警
     * 3. WebSocket推送设备状态变更
     */
    private void handleStatus(String payload) throws Exception {
        JsonNode json = objectMapper.readTree(payload);
        String deviceId = json.get("deviceId").asText();
        String status = json.get("status").asText();

        // 获取设备旧状态
        Optional<Device> optDevice = deviceService.getDeviceByDeviceId(deviceId);
        String oldStatus = optDevice.map(Device::getStatus).orElse("offline");

        // 1. 更新心跳（同时将设备标记为online）
        deviceService.updateHeartbeat(deviceId);
        log.debug("设备心跳已更新 - deviceId: {}, status: {}", deviceId, status);

        // 2. 如果设备从离线恢复上线，自动关闭未处理的离线告警
        if ("online".equals(status) && "offline".equals(oldStatus)) {
            log.info("设备恢复上线，自动关闭离线告警 - deviceId: {}", deviceId);
            alarmService.resolveAlarmsByDeviceId(deviceId);
        }

        // 3. WebSocket推送设备状态变更
        deviceService.getDeviceByDeviceId(deviceId).ifPresent(device -> {
            webSocketHandler.pushDeviceStatus(deviceId, device.getStatus(), device.getLightStatus());
        });
    }

    /**
     * 处理设备控制响应
     * 1. 从Topic中提取deviceId
     * 2. 更新控制日志执行结果
     * 3. WebSocket推送控制结果反馈
     */
    private void handleControlResponse(String topic, String payload) throws Exception {
        // 从Topic提取deviceId: streetlight/{deviceId}/control/response
        String deviceId = extractDeviceIdFromTopic(topic);
        if (deviceId == null) {
            log.error("无法从Topic中提取设备ID: {}", topic);
            return;
        }

        JsonNode json = objectMapper.readTree(payload);
        String command = json.get("command").asText();
        String result = json.get("result").asText();

        log.info("设备控制响应 - deviceId: {}, command: {}, result: {}", deviceId, command, result);

        // 更新控制日志结果
        controlService.updateControlLogResult(deviceId, command, result);

        // WebSocket推送控制结果
        webSocketHandler.pushControlResult(deviceId, command, result);
    }

    /**
     * 从MQTT Topic中提取设备ID
     * Topic格式: streetlight/{deviceId}/...
     */
    private String extractDeviceIdFromTopic(String topic) {
        String[] parts = topic.split("/");
        if (parts.length >= 2) {
            return parts[1];
        }
        return null;
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // 消息送达回调
    }
}
