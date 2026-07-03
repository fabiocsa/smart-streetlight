package com.streetlight.service;

import com.streetlight.entity.SensorData;
import com.streetlight.repository.SensorDataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SensorDataService {

    private final SensorDataRepository sensorDataRepository;

    public SensorData saveSensorData(SensorData data) {
        return sensorDataRepository.save(data);
    }

    public Optional<SensorData> getLatestByDeviceId(String deviceId) {
        return sensorDataRepository.findTopByDeviceIdOrderByReportedAtDesc(deviceId);
    }

    public List<SensorData> getHistory(String deviceId, LocalDateTime start, LocalDateTime end) {
        return sensorDataRepository.findByDeviceIdAndReportedAtBetweenOrderByReportedAtAsc(
                deviceId, start, end);
    }

    public Map<String, Object> getStats(String deviceId, LocalDateTime start, LocalDateTime end) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("avg", sensorDataRepository.avgLightIntensity(deviceId, start, end));
        stats.put("max", sensorDataRepository.maxLightIntensity(deviceId, start, end));
        stats.put("min", sensorDataRepository.minLightIntensity(deviceId, start, end));
        stats.put("count", sensorDataRepository.countByDeviceIdAndTimeRange(deviceId, start, end));
        return stats;
    }
}
