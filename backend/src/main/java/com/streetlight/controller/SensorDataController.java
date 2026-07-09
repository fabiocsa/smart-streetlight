package com.streetlight.controller;

import com.streetlight.entity.SensorData;
import com.streetlight.service.SensorDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/devices/{deviceId}/sensor-data")
@RequiredArgsConstructor
public class SensorDataController {

    private final SensorDataService sensorDataService;

    @GetMapping("/latest")
    public ResponseEntity<SensorData> getLatest(@PathVariable String deviceId) {
        return sensorDataService.getLatestByDeviceId(deviceId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/latest/{sensorType}")
    public ResponseEntity<SensorData> getLatestByType(@PathVariable String deviceId,
                                                       @PathVariable String sensorType) {
        return sensorDataService.getLatestByDeviceIdAndSensorType(deviceId, sensorType)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<SensorData>> getHistory(
            @PathVariable String deviceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(required = false) String sensorType) {
        if (sensorType != null && !sensorType.isBlank()) {
            return ResponseEntity.ok(sensorDataService.getHistoryBySensorType(deviceId, sensorType, start, end));
        }
        return ResponseEntity.ok(sensorDataService.getHistory(deviceId, start, end));
    }

    /**
     * 聚合统计：支持指定指标名。
     * 例: GET /api/devices/SL-001/sensor-data/stats?metric=temperature&start=...&end=...
     * 默认 metric=lightIntensity（向后兼容）。
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "lightIntensity") String metric,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return ResponseEntity.ok(sensorDataService.getStats(deviceId, metric, start, end));
    }
}
