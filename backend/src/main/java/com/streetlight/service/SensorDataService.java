package com.streetlight.service;

import com.streetlight.entity.SensorData;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface SensorDataService {

    SensorData saveAndAutoControl(String deviceId, Double lightIntensity, LocalDateTime reportedAt);

    SensorData saveSensorData(SensorData sensorData);

    Optional<SensorData> getLatestByDeviceId(String deviceId);

    List<SensorData> getHistory(String deviceId, LocalDateTime start, LocalDateTime end);

    Map<String, Object> getStats(String deviceId, LocalDateTime start, LocalDateTime end);
}
