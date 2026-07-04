package com.streetlight.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
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
        // 处理前端发来的消息（如心跳）
        log.debug("收到WebSocket消息: {}", message.getPayload());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket传输错误: {}", exception.getMessage());
        sessions.remove(session.getId());
    }

    /**
     * 广播消息给所有连接的客户端
     */
    public void broadcast(Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            TextMessage message = new TextMessage(json);
            for (WebSocketSession session : sessions.values()) {
                if (session.isOpen()) {
                    session.sendMessage(message);
                }
            }
        } catch (IOException e) {
            log.error("WebSocket广播失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 发送传感器实时数据
     */
    public void pushSensorData(String deviceId, double lightIntensity, String reportedAt) {
        broadcast(Map.of(
                "type", "SENSOR_DATA",
                "deviceId", deviceId,
                "data", Map.of("lightIntensity", lightIntensity, "reportedAt", reportedAt)
        ));
    }

    /**
     * 推送设备状态变更
     */
    public void pushDeviceStatus(String deviceId, String status, String lightStatus) {
        broadcast(Map.of(
                "type", "DEVICE_STATUS",
                "deviceId", deviceId,
                "data", Map.of("status", status, "lightStatus", lightStatus)
        ));
    }

    /**
     * 推送新告警
     */
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

    /**
     * 推送控制结果
     */
    public void pushControlResult(String deviceId, String command, String result) {
        broadcast(Map.of(
                "type", "CONTROL_RESULT",
                "deviceId", deviceId,
                "data", Map.of("command", command, "result", result)
        ));
    }

    public int getSessionCount() {
        return sessions.size();
    }
}
