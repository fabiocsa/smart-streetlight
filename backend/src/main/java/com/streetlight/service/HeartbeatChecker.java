package com.streetlight.service;

import com.streetlight.entity.AlarmLog;
import com.streetlight.entity.Device;
import com.streetlight.enums.AlarmSeverity;
import com.streetlight.enums.AlarmStatus;
import com.streetlight.enums.AlarmType;
import com.streetlight.repository.AlarmLogRepository;
import com.streetlight.repository.DeviceRepository;
import com.streetlight.websocket.WebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 心跳检测定时任务
 * 定期检查在线设备的心跳时间，超过阈值未更新则标记为离线并触发告警
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HeartbeatChecker {

    private final DeviceRepository deviceRepository;
    private final AlarmLogRepository alarmLogRepository;
    private final WebSocketHandler webSocketHandler;

    @Value("${streetlight.heartbeat.timeout:30}")
    private int offlineThresholdSeconds;

    /**
     * 定时检查所有在线设备的心跳状态，每5秒执行一次
     */
    @Scheduled(fixedRate = 5000)
    @Transactional
    public void checkHeartbeats() {
        // 计算离线阈值时间点
        LocalDateTime thresholdTime = LocalDateTime.now().minusSeconds(offlineThresholdSeconds);

        // 查找所有标记为在线但心跳时间超过阈值的设备
        List<Device> offlineDevices = deviceRepository.findByStatusAndLastHeartbeatBefore("online", thresholdTime);

        for (Device device : offlineDevices) {
            // 标记设备为离线
            device.setStatus("offline");
            deviceRepository.save(device);

            // 检查是否已有未处理的离线告警，避免重复创建
            List<AlarmLog> existing = alarmLogRepository.findByDeviceIdAndStatus(device.getDeviceId(), AlarmStatus.PENDING);
            boolean hasPendingOffline = existing.stream()
                    .anyMatch(a -> a.getAlarmType() == AlarmType.OFFLINE);

            if (!hasPendingOffline) {
                // 创建离线告警
                AlarmLog alarm = AlarmLog.builder()
                        .deviceId(device.getDeviceId())
                        .alarmType(AlarmType.OFFLINE)
                        .content(String.format("设备 %s 已离线超过%d秒", device.getDeviceId(), offlineThresholdSeconds))
                        .severity(AlarmSeverity.WARNING)
                        .status(AlarmStatus.PENDING)
                        .build();
                alarmLogRepository.save(alarm);

                log.warn("设备离线告警 - deviceId: {}, content: {}", device.getDeviceId(), alarm.getContent());

                // WebSocket推送新告警
                webSocketHandler.pushNewAlarm(
                        alarm.getId(),
                        device.getDeviceId(),
                        "offline",
                        alarm.getContent(),
                        "warning"
                );
            }

            // WebSocket推送设备状态变更
            webSocketHandler.pushDeviceStatus(device.getDeviceId(), "offline", device.getLightStatus());
        }

        if (!offlineDevices.isEmpty()) {
            log.info("心跳检测完成 - 发现 {} 台设备离线", offlineDevices.size());
        }
    }
}
