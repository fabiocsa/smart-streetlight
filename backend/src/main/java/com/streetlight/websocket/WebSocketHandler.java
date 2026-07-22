package com.streetlight.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class WebSocketHandler extends TextWebSocketHandler {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        log.info("WebSocket连接建立: {} (当前连接数: {})", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("WebSocket连接关闭: {} (当前连接数: {})", session.getId(), sessions.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("收到WebSocket消息: {}", message.getPayload());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket传输错误: {}", exception.getMessage());
        sessions.remove(session.getId());
    }

    /** 广播消息给所有连接的客户端。无连接时跳过序列化。单个 session 失败不影响其余。 */
    public void broadcast(Object payload) {
        if (sessions.isEmpty()) return;
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            log.error("WebSocket JSON序列化失败: {}", e.getMessage(), e);
            return;
        }
        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions.values()) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(message);
                } catch (IOException e) {
                    log.warn("WebSocket发送失败 (session={}): {}", session.getId(), e.getMessage());
                    // 清理已断开的 session，避免后续广播再次尝试
                    sessions.remove(session.getId());
                }
            }
        }
    }

    /**
     * 推送传感器实时数据（多字段版本）。
     * 前端收到后将包含所有传感器指标（lightIntensity, temperature, humidity, voltage, power 等）。
     */
    public void pushSensorData(String deviceId, String sensorType,
                                Map<String, Object> data, String reportedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "SENSOR_DATA");
        payload.put("deviceId", deviceId);
        payload.put("sensorType", sensorType);
        payload.put("data", data);
        payload.put("reportedAt", reportedAt);
        broadcast(payload);
    }

    /** 推送设备状态变更（含控制模式，前端可据此实时更新列表/仪表盘） */
    public void pushDeviceStatus(String deviceId, String status, String lightStatus) {
        pushDeviceStatus(deviceId, status, lightStatus, null);
    }

    /** 推送设备状态变更（含控制模式） */
    public void pushDeviceStatus(String deviceId, String status, String lightStatus, String controlMode) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("lightStatus", lightStatus);
        if (controlMode != null) data.put("controlMode", controlMode);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "DEVICE_STATUS");
        payload.put("deviceId", deviceId);
        payload.put("data", data);
        broadcast(payload);
    }

    /** 推送新告警 */
    public void pushNewAlarm(Long alarmId, String deviceId, String alarmType,
                             String content, String severity) {
        broadcast(Map.of(
                "type", "NEW_ALARM",
                "data", Map.of(
                        "id", alarmId, "deviceId", deviceId,
                        "alarmType", alarmType, "content", content, "severity", severity
                )
        ));
    }

    /** 推送控制结果 */
    public void pushControlResult(String deviceId, String command, String result, String source) {
        broadcast(Map.of(
                "type", "CONTROL_RESULT",
                "deviceId", deviceId,
                "data", Map.of("command", command, "result", result, "source", source != null ? source : "manual")
        ));
    }

    public int getSessionCount() {
        return sessions.size();
    }
}
