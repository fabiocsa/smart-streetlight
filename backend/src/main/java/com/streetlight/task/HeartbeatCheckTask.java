package com.streetlight.task;

import com.streetlight.entity.Device;
import com.streetlight.enums.DeviceStatus;
import com.streetlight.repository.DeviceRepository;
import com.streetlight.service.AlarmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class HeartbeatCheckTask {

    private final DeviceRepository deviceRepository;
    private final AlarmService alarmService;

    @Value("${streetlight.heartbeat.timeout:30}")
    private int heartbeatTimeout;

    @Scheduled(fixedRate = 5000)
    public void checkHeartbeat() {
        List<Device> onlineDevices = deviceRepository.findByStatus("online");
        LocalDateTime now = LocalDateTime.now();
        for (Device device : onlineDevices) {
            if (device.getLastHeartbeat() == null) continue;
            long secondsSinceHb = Duration.between(device.getLastHeartbeat(), now).getSeconds();
            if (secondsSinceHb > heartbeatTimeout) {
                device.setStatus("offline");
                deviceRepository.save(device);
                alarmService.createOfflineAlarm(device.getDeviceId());
                log.warn("设备离线: deviceId={}, 已离线{}秒", device.getDeviceId(), secondsSinceHb);
            }
        }
    }
}
