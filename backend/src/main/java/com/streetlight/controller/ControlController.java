package com.streetlight.controller;

import com.streetlight.common.Result;
import com.streetlight.dto.ControlRequest;
import com.streetlight.dto.ControlModeRequest;
import com.streetlight.dto.ThresholdRequest;
import com.streetlight.entity.ControlLog;
import com.streetlight.service.ControlService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class ControlController {

    private final ControlService controlService;

    @PostMapping("/{deviceId}/control")
    public Result<Map<String, Object>> sendCommand(
            @PathVariable String deviceId,
            @Valid @RequestBody ControlRequest request) {
        String source = request.getSource() != null ? request.getSource() : "manual";
        controlService.sendControlCommand(deviceId, request.getCommand(), source);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", request.getCommand());
        result.put("source", source);
        result.put("result", "success");
        result.put("createdAt", LocalDateTime.now().toString());
        return Result.success(result);
    }

    @GetMapping("/{deviceId}/control/logs")
    public Result<List<ControlLog>> getControlLogs(@PathVariable String deviceId) {
        return Result.success(controlService.getControlLogs(deviceId));
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
}
