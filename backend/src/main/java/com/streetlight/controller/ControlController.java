package com.streetlight.controller;

import com.streetlight.common.Result;
import com.streetlight.dto.BatchControlRequest;
import com.streetlight.dto.ControlModeRequest;
import com.streetlight.dto.ControlRequest;
import com.streetlight.dto.BatchThresholdRequest;
import com.streetlight.dto.SensorStrategyRequest;
import com.streetlight.dto.ThresholdRequest;
import com.streetlight.entity.ControlLog;
import com.streetlight.service.ControlService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class ControlController {

    private final ControlService controlService;

    @PostMapping("/{deviceId}/control")
    public Result<Map<String, Object>> sendCommand(
            @PathVariable String deviceId,
            @Valid @RequestBody ControlRequest request) {
        controlService.sendControlCommand(deviceId, request.getCommand(), "manual", request.getBrightness());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", request.getCommand());
        result.put("source", "manual");
        result.put("result", "success");
        result.put("createdAt", LocalDateTime.now().toString());
        if (request.getBrightness() != null) {
            result.put("brightness", request.getBrightness());
        }
        return Result.success(result);
    }

    @PostMapping("/batch/control")
    public Result<Map<String, Object>> sendBatchCommand(
            @Valid @RequestBody BatchControlRequest request) {
        List<Map<String, Object>> items = controlService.sendBatchControlCommand(
                request.getDeviceIds(), request.getCommand(), request.getBrightness());
        long successCount = items.stream().filter(i -> "success".equals(i.get("result"))).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", request.getCommand());
        result.put("total", items.size());
        result.put("successCount", successCount);
        result.put("failCount", items.size() - successCount);
        result.put("items", items);
        return Result.success(result);
    }

    @GetMapping("/{deviceId}/control-logs")
    public Result<Map<String, Object>> getControlLogs(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ControlLog> logPage = controlService.getControlLogs(deviceId, PageRequest.of(page, size));
        List<Map<String, Object>> items = logPage.getContent().stream().map(log -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", log.getId());
            map.put("deviceId", log.getDeviceId());
            map.put("command", log.getCommand());
            map.put("source", log.getSource());
            map.put("result", log.getResult());
            map.put("createdAt", log.getCreatedAt().toString());
            return map;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", items);
        result.put("totalElements", logPage.getTotalElements());
        result.put("totalPages", logPage.getTotalPages());
        result.put("currentPage", page);
        return Result.success(result);
    }

    @PutMapping("/{id}/control-mode")
    public Result<Void> setControlMode(
            @PathVariable Long id,
            @Valid @RequestBody ControlModeRequest request) {
        controlService.setControlMode(id, request.getControlMode());
        return Result.success();
    }

    @PutMapping("/{id}/threshold")
    public Result<Void> setThreshold(
            @PathVariable Long id,
            @Valid @RequestBody ThresholdRequest request) {
        controlService.setThreshold(id, request.getThresholdOn(), request.getThresholdOff());
        return Result.success();
    }

    @PutMapping("/batch/threshold")
    public Result<Map<String, Object>> batchSetThreshold(
            @Valid @RequestBody BatchThresholdRequest request) {
        int count = controlService.batchSetThreshold(
                request.getIds(), request.getThresholdOn(), request.getThresholdOff());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("updatedCount", count);
        data.put("total", request.getIds().size());
        return Result.success(data);
    }

    @PutMapping("/{id}/sensor-strategy")
    public Result<Void> setSensorStrategy(
            @PathVariable Long id,
            @Valid @RequestBody SensorStrategyRequest request) {
        controlService.setSensorStrategy(id, request.getSensorStrategy(), request.getPrimarySensorId());
        return Result.success();
    }
}
