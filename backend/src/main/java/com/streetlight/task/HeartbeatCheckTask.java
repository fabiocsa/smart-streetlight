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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 心跳检测定时任务
 * <p>
 * 每 5 秒检查所有在线设备的心跳：
 * - 如果 lastHeartbeat 超过 offline-threshold-seconds（默认30秒）未更新
 * - 则标记设备为 offline
 * - 创建离线告警
 * - WebSocket 推送给前端
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class HeartbeatCheckTask {

    private final DeviceRepository deviceRepository;
    private final AlarmService alarmService;
    private final WebSocketHandler webSocketHandler;

    @Value("${streetlight.offline-threshold-seconds:30}")
    private int offlineThresholdSeconds;

    @Scheduled(fixedRateString = "${streetlight.heartbeat-check-interval-seconds:5}000")
    @Transactional
    public void checkHeartbeats() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(offlineThresholdSeconds);
        List<Device> offlineDevices = deviceRepository
                .findByStatusAndLastHeartbeatBefore("online", cutoff);

        for (Device device : offlineDevices) {
            // 标记离线
            device.setStatus("offline");
            deviceRepository.save(device);
            log.warn("设备离线检测: deviceId={}, 最后心跳={}", device.getDeviceId(), device.getLastHeartbeat());

            // 创建离线告警
            AlarmLog alarm = AlarmLog.builder()
                    .deviceId(device.getDeviceId())
                    .alarmType("offline")
                    .content(String.format("设备 %s 已离线超过%d秒", device.getDeviceId(), offlineThresholdSeconds))
                    .severity("warning")
                    .status("pending")
                    .build();
            AlarmLog savedAlarm = alarmService.createAlarm(alarm);

            // WebSocket 推送设备状态变更
            webSocketHandler.pushDeviceStatus(device.getDeviceId(), "offline", device.getLightStatus());

            // WebSocket 推送新告警
            webSocketHandler.pushNewAlarm(
                    savedAlarm.getId(),
                    savedAlarm.getDeviceId(),
                    savedAlarm.getAlarmType(),
                    savedAlarm.getContent(),
                    savedAlarm.getSeverity()
            );
        }

        if (!offlineDevices.isEmpty()) {
            log.info("心跳检测完成: {} 台设备已标记离线", offlineDevices.size());
        }
    }
}
