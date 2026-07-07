package com.streetlight.service;

import com.streetlight.entity.SensorData;
import com.streetlight.repository.SensorDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class SensorDataService {

    private final SensorDataRepository sensorDataRepository;

    public SensorData saveSensorData(SensorData data) {
        SensorData saved = sensorDataRepository.save(data);
        log.trace("传感器数据已保存 - deviceId: {}, lightIntensity: {}, reportedAt: {}",
                saved.getDeviceId(), saved.getLightIntensity(), saved.getReportedAt());
        return saved;
    }

    public Optional<SensorData> getLatestByDeviceId(String deviceId) {
        return sensorDataRepository.findTopByDeviceIdOrderByReportedAtDesc(deviceId);
    }

    public List<SensorData> getHistory(String deviceId, LocalDateTime start, LocalDateTime end) {
        log.debug("查询传感器历史数据 - deviceId: {}, start: {}, end: {}", deviceId, start, end);
        return sensorDataRepository.findByDeviceIdAndReportedAtBetweenOrderByReportedAtAsc(
                deviceId, start, end);
    }

    public Map<String, Object> getStats(String deviceId, LocalDateTime start, LocalDateTime end) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("avg", sensorDataRepository.avgLightIntensity(deviceId, start, end));
        stats.put("max", sensorDataRepository.maxLightIntensity(deviceId, start, end));
        stats.put("min", sensorDataRepository.minLightIntensity(deviceId, start, end));
        stats.put("count", sensorDataRepository.countByDeviceIdAndTimeRange(deviceId, start, end));
        log.debug("查询传感器统计数据 - deviceId: {}, stats: {}", deviceId, stats);
        return stats;
    }
}
