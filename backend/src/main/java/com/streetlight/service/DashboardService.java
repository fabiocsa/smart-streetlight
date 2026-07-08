package com.streetlight.service;

import com.streetlight.entity.SensorData;
import com.streetlight.repository.AlarmLogRepository;
import com.streetlight.repository.ControlLogRepository;
import com.streetlight.repository.SensorDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardService {

    private final DeviceService deviceService;
    private final AlarmService alarmService;
    private final SensorDataRepository sensorDataRepository;
    private final AlarmLogRepository alarmLogRepository;
    private final ControlLogRepository controlLogRepository;

    // ==================== 基础统计 ====================

    public Map<String, Object> getStats() {
        var devices = deviceService.getAllDevices();

        long totalDevices = devices.size();
        long onlineDevices = devices.stream().filter(d -> "online".equals(d.getStatus())).count();
        long offlineDevices = totalDevices - onlineDevices;
        long lightsOn = devices.stream().filter(d -> "on".equals(d.getLightStatus())).count();
        long lightsOff = totalDevices - lightsOn;
        long pendingAlarms = alarmService.countPendingAlarms();
        long todayAlarms = alarmService.countTodayAlarms();

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long todayDataPoints = sensorDataRepository.countToday(todayStart);
        long todayControls = controlLogRepository.countByCreatedAtBetween(todayStart, LocalDate.now().atTime(LocalTime.MAX));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalDevices", totalDevices);
        stats.put("onlineDevices", onlineDevices);
        stats.put("offlineDevices", offlineDevices);
        stats.put("lightsOn", lightsOn);
        stats.put("lightsOff", lightsOff);
        stats.put("pendingAlarms", pendingAlarms);
        stats.put("todayAlarms", todayAlarms);
        stats.put("todayDataPoints", todayDataPoints);
        stats.put("todayControls", todayControls);
        return stats;
    }

    // ==================== 设备状态分布 ====================

    public List<Map<String, Object>> getDeviceStatusDistribution() {
        var devices = deviceService.getAllDevices();
        long online = devices.stream().filter(d -> "online".equals(d.getStatus())).count();
        long offline = devices.stream().filter(d -> "offline".equals(d.getStatus())).count();
        long autoMode = devices.stream().filter(d -> "auto".equals(d.getControlMode())).count();
        long manualMode = devices.stream().filter(d -> "manual".equals(d.getControlMode())).count();

        return List.of(
            Map.of("name", "在线", "value", online),
            Map.of("name", "离线", "value", offline),
            Map.of("name", "自动模式", "value", autoMode),
            Map.of("name", "手动模式", "value", manualMode)
        );
    }

    // ==================== 最新传感器数据 ====================

    public List<Map<String, Object>> getLatestSensorData() {
        List<SensorData> latestList = sensorDataRepository.findLatestPerDevice();
        return latestList.stream().map(sd -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("deviceId", sd.getDeviceId());
            map.put("lightIntensity", sd.getLightIntensity());
            map.put("reportedAt", sd.getReportedAt());
            return map;
        }).collect(Collectors.toList());
    }

    // ==================== 光照趋势 ====================

    public Map<String, Object> getLightTrend(String deviceId) {
        return getLightTrend(deviceId, "24h");
    }

    public Map<String, Object> getLightTrend(String deviceId, String range) {
        String r = (range != null) ? range.toLowerCase() : "24h";
        boolean isHourly = "24h".equals(r);
        LocalDateTime since;
        int slots;

        switch (r) {
            case "7d":
                since = LocalDate.now().minusDays(6).atStartOfDay();
                slots = 7;
                break;
            case "30d":
                since = LocalDate.now().minusDays(29).atStartOfDay();
                slots = 30;
                break;
            default:
                since = LocalDateTime.now().minusHours(24).withMinute(0).withSecond(0).withNano(0);
                slots = 24;
                break;
        }

        boolean hasDevice = deviceId != null && !deviceId.isEmpty();
        List<String> labels = new ArrayList<>();
        List<Double> values = new ArrayList<>();

        if (isHourly) {
            List<Object[]> rows = hasDevice
                    ? sensorDataRepository.hourlyAvgLightByDevice(deviceId, since)
                    : sensorDataRepository.hourlyAvgLightSince(since);

            Map<Integer, Double> hourMap = new LinkedHashMap<>();
            for (int h = 0; h < 24; h++) hourMap.put(h, 0.0);
            for (Object[] row : rows) {
                int hr = ((Number) row[0]).intValue();
                hourMap.put(hr, Math.round(((Number) row[1]).doubleValue() * 10.0) / 10.0);
            }
            for (Map.Entry<Integer, Double> e : hourMap.entrySet()) {
                labels.add(String.format("%02d:00", e.getKey()));
                values.add(e.getValue());
            }
        } else {
            List<Object[]> rows = hasDevice
                    ? sensorDataRepository.dailyAvgLightByDevice(deviceId, since)
                    : sensorDataRepository.dailyAvgLightSince(since);

            Map<LocalDate, Double> dayMap = new LinkedHashMap<>();
            for (int i = slots - 1; i >= 0; i--) {
                dayMap.put(LocalDate.now().minusDays(i), 0.0);
            }
            for (Object[] row : rows) {
                LocalDate dt = ((java.sql.Date) row[0]).toLocalDate();
                double val = Math.round(((Number) row[1]).doubleValue() * 10.0) / 10.0;
                dayMap.put(dt, val);
            }
            for (Map.Entry<LocalDate, Double> e : dayMap.entrySet()) {
                labels.add(e.getKey().toString().substring(5));
                values.add(e.getValue());
            }
        }

        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        double avg = values.isEmpty() ? 0 : Math.round(sum / values.size() * 10.0) / 10.0;
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double min = values.stream().mapToDouble(Double::doubleValue).filter(v -> v > 0).min().orElse(0);

        long totalPoints = hasDevice
                ? sensorDataRepository.countByDeviceIdAndTimeRange(deviceId, since, LocalDateTime.now())
                : sensorDataRepository.countSince(since);
        boolean demoMode = isHourly ? totalPoints < 10 : totalPoints < slots * 3L;

        Optional<LocalDateTime> lastTime = hasDevice
                ? sensorDataRepository.maxReportedAtByDevice(deviceId)
                : sensorDataRepository.maxReportedAt();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("labels", labels);
        result.put("values", values);
        result.put("range", r);
        result.put("granularity", isHourly ? "hour" : "day");
        result.put("deviceId", hasDevice ? deviceId : "all");
        result.put("avg", avg);
        result.put("max", max);
        result.put("min", min);
        result.put("totalPoints", totalPoints);
        result.put("demoMode", demoMode);
        result.put("lastDataTime", lastTime.map(LocalDateTime::toString).orElse(null));
        return result;
    }

    // ==================== 告警统计 ====================

    public Map<String, Object> getAlarmStats() {
        LocalDateTime since7days = LocalDate.now().minusDays(6).atStartOfDay();

        List<Object[]> dailyRows = alarmLogRepository.countByDaySince(since7days);
        List<String> days = new ArrayList<>();
        List<Long> counts = new ArrayList<>();

        Map<LocalDate, Long> dayMap = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            dayMap.put(LocalDate.now().minusDays(i), 0L);
        }
        for (Object[] row : dailyRows) {
            LocalDate dt = ((java.sql.Date) row[0]).toLocalDate();
            long cnt = ((Number) row[1]).longValue();
            dayMap.put(dt, cnt);
        }
        for (Map.Entry<LocalDate, Long> e : dayMap.entrySet()) {
            days.add(e.getKey().toString().substring(5));
            counts.add(e.getValue());
        }

        LocalDateTime sinceMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        List<Object[]> severityRows = alarmLogRepository.countBySeveritySince(sinceMonth);
        long criticalCount = 0, warningCount = 0, infoCount = 0;
        for (Object[] row : severityRows) {
            String sev = row[0].toString();
            long cnt = ((Number) row[1]).longValue();
            if ("CRITICAL".equalsIgnoreCase(sev)) criticalCount = cnt;
            else if ("WARNING".equalsIgnoreCase(sev)) warningCount = cnt;
            else if ("INFO".equalsIgnoreCase(sev)) infoCount = cnt;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", days);
        result.put("dailyCounts", counts);
        result.put("criticalCount", criticalCount);
        result.put("warningCount", warningCount);
        result.put("infoCount", infoCount);
        return result;
    }

    // ==================== 近期告警 ====================

    public List<Map<String, Object>> getRecentAlarms() {
        return alarmLogRepository.findTop10ByOrderByCreatedAtDesc().stream().map(a -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", a.getId());
            map.put("deviceId", a.getDeviceId());
            map.put("alarmType", a.getAlarmType().name());
            map.put("content", a.getContent());
            map.put("severity", a.getSeverity().name());
            map.put("status", a.getStatus().name());
            map.put("createdAt", a.getCreatedAt());
            return map;
        }).collect(Collectors.toList());
    }

    // ==================== 近期控制日志 ====================

    public List<Map<String, Object>> getRecentControls() {
        return controlLogRepository.findTop20ByOrderByCreatedAtDesc().stream().map(c -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", c.getId());
            map.put("deviceId", c.getDeviceId());
            map.put("command", c.getCommand());
            map.put("source", c.getSource());
            map.put("result", c.getResult());
            map.put("createdAt", c.getCreatedAt());
            return map;
        }).collect(Collectors.toList());
    }
}
