package com.streetlight.controller;

import com.streetlight.common.Result;
import com.streetlight.dto.ResolveAlarmRequest;
import com.streetlight.entity.AlarmLog;
import com.streetlight.service.AlarmService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/alarms")
@RequiredArgsConstructor
public class AlarmController {

    private final AlarmService alarmService;

    @GetMapping
    public Result<Page<AlarmLog>> getAlarms(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return Result.success(alarmService.listAlarms(pageable, status, type));
    }

    @PutMapping("/{id}/resolve")
    public Result<Void> resolveAlarm(
            @PathVariable Long id,
            @Valid @RequestBody ResolveAlarmRequest request) {
        alarmService.resolveAlarm(id, request.getResolvedBy());
        return Result.success();
    }
}
