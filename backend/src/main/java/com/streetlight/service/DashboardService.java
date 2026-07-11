package com.streetlight.service;

import com.streetlight.entity.Device;
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

    /**
     * 获取各设备最新传感器数据（含完整 data_json）。
     * 只返回最近 10 分钟内有数据上报的设备，过滤过期垃圾数据。
     */
    public List<Map<String, Object>> getLatestSensorData() {
        List<SensorData> latestList = sensorDataRepository.findLatestPerDevice();
        LocalDateTime recentThreshold = LocalDateTime.now().minusMinutes(10);
        return latestList.stream()
            .filter(sd -> sd.getReportedAt() != null && sd.getReportedAt().isAfter(recentThreshold))
            .filter(sd -> sd.getDeviceId() != null && !sd.getDeviceId().startsWith("sensor_"))
            .map(sd -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("deviceId", sd.getDeviceId());
                map.put("sensorType", sd.getSensorType());
                map.put("data", sd.getData());         // 完整多维数据
                map.put("reportedAt", sd.getReportedAt());
                // 向后兼容：也提供单独字段
                map.put("lightIntensity", sd.getLightIntensity());
                return map;
            }).collect(Collectors.toList());
    }

    /**
     * 按传感器类型获取各设备最新数据。
     * 只返回最近 10 分钟内有数据上报的设备。
     */
    public List<Map<String, Object>> getLatestSensorDataByType(String sensorType) {
        List<SensorData> latestList = sensorDataRepository.findLatestPerDeviceBySensorType(sensorType);
        LocalDateTime recentThreshold = LocalDateTime.now().minusMinutes(10);
        return latestList.stream()
            .filter(sd -> sd.getReportedAt() != null && sd.getReportedAt().isAfter(recentThreshold))
            .filter(sd -> sd.getDeviceId() != null && !sd.getDeviceId().startsWith("sensor_"))
            .map(sd -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("deviceId", sd.getDeviceId());
                map.put("sensorType", sd.getSensorType());
                map.put("data", sd.getData());
                map.put("reportedAt", sd.getReportedAt());
                map.put("lightIntensity", sd.getLightIntensity());
                return map;
            }).collect(Collectors.toList());
    }

    // ==================== 光照趋势（兼容旧 API） ====================

    public Map<String, Object> getLightTrend(String deviceId) {
        return getSensorTrend(deviceId, "lightIntensity", "24h");
    }

    public Map<String, Object> getLightTrend(String deviceId, String range) {
        return getSensorTrend(deviceId, "lightIntensity", range);
    }

    // ==================== 通用传感器趋势 ====================

    /**
     * 获取传感器指标趋势。
     * @param deviceId 设备 ID（null/empty = 全部设备）
     * @param metric   指标名（data_json 中的 key，如 lightIntensity, temperature, humidity, power）
     * @param range    时间范围: 24h / 7d / 30d
     */
    public Map<String, Object> getSensorTrend(String deviceId, String metric, String range) {
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
        // 注：当前所有模拟器传感器类型均为 "light"，多维数据（温湿度等）都存储在
        // 同一 data_json 列中，因此按 sensorType = "light" 过滤即可查到所有指标。
        // 后续若引入独立温湿度传感器，需根据 metric 动态映射 sensorType。
        String sensorType = "light";
        List<String> labels = new ArrayList<>();
        List<Double> values = new ArrayList<>();

        if (isHourly) {
            // 小时聚合（使用光照专用方法作为默认，其他 metric 用通用方法）
            List<Object[]> rows;
            if ("lightIntensity".equals(metric)) {
                rows = hasDevice
                        ? sensorDataRepository.hourlyAvgLightByDevice(deviceId, since)
                        : sensorDataRepository.hourlyAvgLightSince(since);
            } else {
                rows = hasDevice
                        ? sensorDataRepository.hourlyAvgByDeviceAndSensorType(deviceId, sensorType, metric, since)
                        : sensorDataRepository.hourlyAvgBySensorType(sensorType, metric, since);
            }

            Map<Integer, Double> hourMap = new LinkedHashMap<>();
            for (int h = 0; h < 24; h++) hourMap.put(h, null);
            for (Object[] row : rows) {
                int hr = ((Number) row[0]).intValue();
                if (row[1] != null) {
                    hourMap.put(hr, Math.round(((Number) row[1]).doubleValue() * 10.0) / 10.0);
                }
            }
            for (Map.Entry<Integer, Double> e : hourMap.entrySet()) {
                labels.add(String.format("%02d:00", e.getKey()));
                values.add(e.getValue());
            }
        } else {
            // 天聚合
            List<Object[]> rows;
            if ("lightIntensity".equals(metric)) {
                rows = hasDevice
                        ? sensorDataRepository.dailyAvgLightByDevice(deviceId, since)
                        : sensorDataRepository.dailyAvgLightSince(since);
            } else {
                rows = hasDevice
                        ? sensorDataRepository.dailyAvgByDeviceAndSensorType(deviceId, "light", metric, since)
                        : sensorDataRepository.dailyAvgBySensorType("light", metric, since);
            }

            Map<LocalDate, Double> dayMap = new LinkedHashMap<>();
            for (int i = slots - 1; i >= 0; i--) {
                dayMap.put(LocalDate.now().minusDays(i), null);
            }
            for (Object[] row : rows) {
                LocalDate dt = ((java.sql.Date) row[0]).toLocalDate();
                if (row[1] != null) {
                    double val = Math.round(((Number) row[1]).doubleValue() * 10.0) / 10.0;
                    dayMap.put(dt, val);
                }
            }
            for (Map.Entry<LocalDate, Double> e : dayMap.entrySet()) {
                labels.add(e.getKey().toString().substring(5));
                values.add(e.getValue());
            }
        }

        // 排除 null 值（无数据的时段），计算有效统计
        List<Double> validValues = values.stream().filter(Objects::nonNull).collect(Collectors.toList());
        Double avg = validValues.isEmpty() ? null
                : Math.round(validValues.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10.0) / 10.0;
        Double max = validValues.stream().max(Double::compare).orElse(null);
        Double min = validValues.stream().filter(v -> v > 0).min(Double::compare).orElse(null);

        long totalPoints = hasDevice
                ? sensorDataRepository.countByDeviceIdAndTimeRange(deviceId, since, LocalDateTime.now())
                : sensorDataRepository.countSince(since);
        int nonNullSlots = (int) values.stream().filter(Objects::nonNull).count();
        boolean demoMode = isHourly ? nonNullSlots < 3 : nonNullSlots < Math.max(2, slots / 3);

        Optional<LocalDateTime> lastTime = hasDevice
                ? sensorDataRepository.maxReportedAtByDevice(deviceId)
                : sensorDataRepository.maxReportedAt();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("labels", labels);
        result.put("values", values);
        result.put("metric", metric);
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

    // ==================== 传感器趋势对比（多设备） ====================

    /**
     * 多设备趋势对比。
     * 对每个 deviceId 调用 getSensorTrend() 获取单设备趋势，同时附加设备名称。
     */
    public Map<String, Object> getSensorTrendCompare(List<String> deviceIds, String metric, String range) {
        List<Map<String, Object>> trends = new ArrayList<>();
        for (String deviceId : deviceIds) {
            Map<String, Object> trend = getSensorTrend(deviceId, metric, range);
            // 附加设备名称
            String deviceName = deviceService.getDeviceByDeviceId(deviceId)
                    .map(Device::getName)
                    .orElse(deviceId);
            trend.put("deviceName", deviceName);
            trends.add(trend);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trends", trends);
        result.put("metric", metric);
        result.put("range", range);
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
