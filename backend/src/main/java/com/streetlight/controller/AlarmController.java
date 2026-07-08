package com.streetlight.controller;

import com.streetlight.common.Result;
import com.streetlight.dto.ResolveAlarmRequest;
import com.streetlight.entity.AlarmLog;
import com.streetlight.service.AlarmService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/alarms")
@RequiredArgsConstructor
public class AlarmController {

    private final AlarmService alarmService;

    @GetMapping
    public Result<Page<AlarmLog>> getAlarms(
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(alarmService.listAlarms(page, size, status, type, deviceId));
    }

    @PutMapping("/{id}/resolve")
    public Result<Void> resolveAlarm(
            @PathVariable Long id,
            @Valid @RequestBody ResolveAlarmRequest request) {
        alarmService.resolveAlarm(id, request.getResolvedBy());
        return Result.success();
    }
}
