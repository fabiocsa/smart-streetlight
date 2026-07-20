package com.streetlight.service;

import com.streetlight.entity.Device;
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
    private final AlarmService alarmService;
    private final WebSocketHandler webSocketHandler;

    @Value("${streetlight.heartbeat.timeout:30}")
    private int offlineThresholdSeconds;

    /**
     * 定时检查所有在线设备的心跳状态，每5秒执行一次
     */
    @Scheduled(fixedRate = 5000)
    @Transactional
    public void checkHeartbeats() {
        LocalDateTime thresholdTime = LocalDateTime.now().minusSeconds(offlineThresholdSeconds);

        // 查找所有标记为在线但心跳时间超过阈值的设备
        List<Device> offlineDevices = deviceRepository
                .findByStatusAndLastHeartbeatBefore("online", thresholdTime);

        for (Device device : offlineDevices) {
            // 标记设备为离线，灯光状态设为未知
            device.setStatus("offline");
            device.setLightStatus("unknown");
            deviceRepository.save(device);

            // 委托 AlarmService 创建离线告警（内部处理去重逻辑）
            alarmService.createOfflineAlarm(device.getDeviceId());

            // WebSocket 推送设备状态变更
            webSocketHandler.pushDeviceStatus(device.getDeviceId(), "offline", device.getLightStatus());
        }

        if (!offlineDevices.isEmpty()) {
            log.info("心跳检测完成 - 发现 {} 台设备离线", offlineDevices.size());
        }
    }
}
