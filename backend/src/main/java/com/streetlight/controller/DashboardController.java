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

    @GetMapping("/light-trend")
    public ResponseEntity<Map<String, Object>> getLightTrend(
            @RequestParam(required = false) String deviceId,
            @RequestParam(defaultValue = "24h") String range) {
        return ResponseEntity.ok(dashboardService.getLightTrend(deviceId, range));
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
