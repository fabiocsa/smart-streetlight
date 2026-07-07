package com.streetlight.task;

import com.streetlight.entity.AlarmLog;
import com.streetlight.entity.Device;
import com.streetlight.repository.DeviceRepository;
import com.streetlight.service.AlarmService;
import com.streetlight.websocket.WebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 心跳检测定时任务
 *
 * 每5秒检查所有在线设备的心跳状态。
 * 如果设备最后心跳时间超过阈值（默认30秒），则：
 * 1. 将设备标记为 offline
 * 2. 创建一条离线告警
 * 3. WebSocket 推送给前端
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatCheckTask {

    private final DeviceRepository deviceRepository;
    private final AlarmService alarmService;
    private final WebSocketHandler webSocketHandler;

    @Value("${streetlight.offline-threshold-seconds:30}")
    private int offlineThresholdSeconds;

    /**
     * 每5秒执行一次心跳检查
     * 对应配置 streetlight.heartbeat-check-interval-seconds = 5
     */
    @Scheduled(fixedDelay = 5000)
    public void checkHeartbeat() {
        LocalDateTime thresholdTime = LocalDateTime.now().minusSeconds(offlineThresholdSeconds);
        List<Device> overdueDevices = deviceRepository.findByStatusAndLastHeartbeatBefore("online", thresholdTime);

        if (overdueDevices.isEmpty()) {
            return;
        }

        log.warn("检测到 {} 个设备心跳超时(阈值={}秒)", overdueDevices.size(), offlineThresholdSeconds);

        for (Device device : overdueDevices) {
            // 1. 标记设备离线
            String deviceId = device.getDeviceId();
            device.setStatus("offline");
            deviceRepository.save(device);
            log.warn("设备已标记离线 - deviceId: {}, lastHeartbeat: {}",
                    deviceId, device.getLastHeartbeat());

            // 2. 创建离线告警
            String alarmContent = String.format("设备 %s 已离线超过%d秒", deviceId, offlineThresholdSeconds);
            AlarmLog alarm = AlarmLog.builder()
                    .deviceId(deviceId)
                    .alarmType("offline")
                    .content(alarmContent)
                    .severity("warning")
                    .status("pending")
                    .build();
            alarmService.createAlarm(alarm);
            log.info("离线告警已创建 - deviceId: {}, alarmId: {}", deviceId, alarm.getId());

            // 3. WebSocket 推送设备状态变更 + 新告警通知
            webSocketHandler.pushDeviceStatus(deviceId, "offline", device.getLightStatus());
            webSocketHandler.pushNewAlarm(alarm.getId(), deviceId,
                    "offline", alarmContent, "warning");
        }
    }
}
