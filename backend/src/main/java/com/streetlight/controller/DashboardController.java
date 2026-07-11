package com.streetlight.controller;

import com.streetlight.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(dashboardService.getStats());
    }

    @GetMapping("/device-status-distribution")
    public ResponseEntity<List<Map<String, Object>>> getDeviceStatusDistribution() {
        return ResponseEntity.ok(dashboardService.getDeviceStatusDistribution());
    }

    @GetMapping("/latest-sensor-data")
    public ResponseEntity<List<Map<String, Object>>> getLatestSensorData() {
        return ResponseEntity.ok(dashboardService.getLatestSensorData());
    }

    @GetMapping("/latest-sensor-data/{sensorType}")
    public ResponseEntity<List<Map<String, Object>>> getLatestSensorDataByType(
            @PathVariable String sensorType) {
        return ResponseEntity.ok(dashboardService.getLatestSensorDataByType(sensorType));
    }

    /**
     * 通用传感器趋势（新接口）。
     * 例: GET /api/dashboard/sensor-trend?metric=temperature&deviceId=SL-001&range=24h
     * 默认 metric=lightIntensity
     */
    @GetMapping("/sensor-trend")
    public ResponseEntity<Map<String, Object>> getSensorTrend(
            @RequestParam(required = false) String deviceId,
            @RequestParam(defaultValue = "lightIntensity") String metric,
            @RequestParam(defaultValue = "24h") String range) {
        return ResponseEntity.ok(dashboardService.getSensorTrend(deviceId, metric, range));
    }

    /**
     * 光照趋势（旧接口，保留兼容）。
     * 内部重定向到 getSensorTrend(deviceId, "lightIntensity", range)。
     */
    @GetMapping("/light-trend")
    public ResponseEntity<Map<String, Object>> getLightTrend(
            @RequestParam(required = false) String deviceId,
            @RequestParam(defaultValue = "24h") String range) {
        return ResponseEntity.ok(dashboardService.getLightTrend(deviceId, range));
    }

    /**
     * 多设备趋势对比。
     * POST /api/dashboard/sensor-trend-compare
     * Body: { "deviceIds": ["SL-001","SL-002"], "metric": "lightIntensity", "range": "24h" }
     */
    @PostMapping("/sensor-trend-compare")
    public ResponseEntity<Map<String, Object>> getSensorTrendCompare(
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> deviceIds = (List<String>) body.get("deviceIds");
        String metric = (String) body.getOrDefault("metric", "lightIntensity");
        String range = (String) body.getOrDefault("range", "24h");
        return ResponseEntity.ok(dashboardService.getSensorTrendCompare(deviceIds, metric, range));
    }

    @GetMapping("/alarm-stats")
    public ResponseEntity<Map<String, Object>> getAlarmStats() {
        return ResponseEntity.ok(dashboardService.getAlarmStats());
    }

    @GetMapping("/recent-alarms")
    public ResponseEntity<List<Map<String, Object>>> getRecentAlarms() {
        return ResponseEntity.ok(dashboardService.getRecentAlarms());
    }

    @GetMapping("/recent-controls")
    public ResponseEntity<List<Map<String, Object>>> getRecentControls() {
        return ResponseEntity.ok(dashboardService.getRecentControls());
    }
}
