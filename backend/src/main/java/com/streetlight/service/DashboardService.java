package com.streetlight.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final DeviceService deviceService;
    private final AlarmService alarmService;

    public Map<String, Object> getStats() {
        var devices = deviceService.getAllDevices();

        long totalDevices = devices.size();
        long onlineDevices = devices.stream().filter(d -> "online".equals(d.getStatus())).count();
        long offlineDevices = totalDevices - onlineDevices;
        long lightsOn = devices.stream().filter(d -> "on".equals(d.getLightStatus())).count();
        long lightsOff = totalDevices - lightsOn;
        long pendingAlarms = alarmService.countPendingAlarms();
        long todayAlarms = alarmService.countTodayAlarms();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalDevices", totalDevices);
        stats.put("onlineDevices", onlineDevices);
        stats.put("offlineDevices", offlineDevices);
        stats.put("lightsOn", lightsOn);
        stats.put("lightsOff", lightsOff);
        stats.put("pendingAlarms", pendingAlarms);
        stats.put("todayAlarms", todayAlarms);
        return stats;
    }
}
