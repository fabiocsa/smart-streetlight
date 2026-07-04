package com.streetlight.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streetlight.entity.ControlLog;
import com.streetlight.entity.SensorData;
import com.streetlight.service.ControlService;
import com.streetlight.service.DeviceService;
import com.streetlight.service.SensorDataService;
import com.streetlight.websocket.WebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * MQTT 消息消费者 — 处理所有设备上报的消息
 * <p>
 * 按 topic 路由：
 * - streetlight/{deviceId}/sensor/data     → 传感器数据入库 + WebSocket推送
 * - streetlight/{deviceId}/status          → 设备心跳更新
 * - streetlight/{deviceId}/control/response → 控制结果记录
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MqttMessageHandler {

    private final ObjectMapper objectMapper;
    private final SensorDataService sensorDataService;
    private final DeviceService deviceService;
    private final ControlService controlService;
    private final WebSocketHandler webSocketHandler;

    @ServiceActivator(inputChannel = "mqttInputChannel")
    public void handleMessage(Message<?> message) {
        String payload = (String) message.getPayload();
        String topic = (String) message.getHeaders().get("mqtt_topic");
        log.info("收到MQTT消息 - topic: {}, payload: {}", topic, payload);

        try {
            if (topic != null && topic.contains("/sensor/data")) {
                handleSensorData(payload);
            } else if (topic != null && topic.contains("/status")) {
                handleStatus(payload);
            } else if (topic != null && topic.contains("/control/response")) {
                handleControlResponse(payload);
            }
        } catch (Exception e) {
            log.error("处理MQTT消息失败 - topic: {}: {}", topic, e.getMessage(), e);
        }
    }

    // ==================== 传感器数据 ====================

    /**
     * 处理传感器数据上报
     * 格式: {"deviceId":"SL-001","lightIntensity":125.5,"timestamp":"2026-07-01T10:00:00Z"}
     */
    private void handleSensorData(String payload) throws Exception {
        LightData lightData = objectMapper.readValue(payload, LightData.class);
        log.debug("解析到传感器数据: deviceId={}, lightIntensity={}, reportedAt={}",
                lightData.getDeviceId(), lightData.getLightIntensity(), lightData.getReportedAt());

        // 保存到数据库
        SensorData sensorData = SensorData.builder()
                .deviceId(lightData.getDeviceId())
                .lightIntensity(lightData.getLightIntensity())
                .reportedAt(parseTime(lightData.getReportedAt()))
                .build();
        sensorDataService.saveSensorData(sensorData);
        log.info("传感器数据已入库: deviceId={}, lightIntensity={}",
                lightData.getDeviceId(), lightData.getLightIntensity());

        // WebSocket 推送实时数据
        webSocketHandler.pushSensorData(
                lightData.getDeviceId(),
                lightData.getLightIntensity(),
                lightData.getReportedAt() != null ? lightData.getReportedAt() : LocalDateTime.now().toString()
        );

        // 更新设备心跳
        deviceService.updateHeartbeat(lightData.getDeviceId());

        // 触发自动控制
        deviceService.getDeviceByDeviceId(lightData.getDeviceId()).ifPresent(device -> {
            String command = controlService.evaluateAutoControl(device, lightData.getLightIntensity());
            if (command != null) {
                webSocketHandler.pushControlResult(lightData.getDeviceId(), command, "sent");
            }
        });
    }

    // ==================== 设备心跳 ====================

    /**
     * 处理设备心跳/状态上报
     * 格式: {"deviceId":"SL-001","status":"online","battery":85}
     */
    private void handleStatus(String payload) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = objectMapper.readValue(payload, Map.class);
        String deviceId = (String) data.get("deviceId");

        if (deviceId == null) {
            log.warn("心跳数据缺少deviceId: {}", payload);
            return;
        }

        // 更新设备心跳和在线状态
        deviceService.updateHeartbeat(deviceId);
        log.debug("设备心跳已更新: deviceId={}", deviceId);

        // 自动关闭该设备的离线告警
        deviceService.getDeviceByDeviceId(deviceId).ifPresent(device -> {
            if ("offline".equals(device.getStatus())) {
                log.info("设备重新上线: deviceId={}", deviceId);
            }
        });
    }

    // ==================== 控制响应 ====================

    /**
     * 处理控制指令执行结果
     * 格式: {"command":"on","result":"success","timestamp":"2026-07-01T10:00:01Z"}
     */
    private void handleControlResponse(String payload) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = objectMapper.readValue(payload, Map.class);
        String command = (String) data.get("command");
        String result = (String) data.get("result");

        // 从topic中提取deviceId
        String deviceId = extractDeviceIdFromTopic(payload);
        if (deviceId == null) {
            log.warn("控制响应缺少deviceId信息");
            return;
        }

        log.info("控制执行结果: deviceId={}, command={}, result={}", deviceId, command, result);

        // WebSocket 推送控制结果
        webSocketHandler.pushControlResult(deviceId, command, result);
    }

    // ==================== 工具方法 ====================

    /**
     * 从topic中提取deviceId
     * topic格式: streetlight/{deviceId}/xxx
     */
    private String extractDeviceIdFromTopic(String payload) {
        // 尝试从payload中解析deviceId
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(payload, Map.class);
            return (String) data.get("deviceId");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析时间字符串，支持多种 ISO 格式
     */
    private LocalDateTime parseTime(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            String normalized = timeStr
                    .replace("Z", "")
                    .replaceAll("[+-]\\d{2}:\\d{2}$", "");
            return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            log.warn("解析时间失败: {}, 使用当前时间", timeStr);
            return LocalDateTime.now();
        }
    }
}
