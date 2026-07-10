package com.streetlight.controller;

import com.streetlight.common.Result;
import com.streetlight.dto.ResolveAlarmRequest;
import com.streetlight.entity.AlarmLog;
import com.streetlight.service.AlarmService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alarms")
@RequiredArgsConstructor
public class AlarmController {

    private final AlarmService alarmService;

    /** 分页查询告警列表，支持多条件筛选和排序 */
    @GetMapping
    public Result<Map<String, Object>> getAlarms(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AlarmLog> resultPage = alarmService.listAlarms(page, size, status, type,
                severity, deviceId, keyword, sort, order);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", resultPage.getContent());
        result.put("totalElements", resultPage.getTotalElements());
        result.put("totalPages", resultPage.getTotalPages());
        result.put("currentPage", page);
        return Result.success(result);
    }

    /** 处理单个告警（含备注） */
    @PutMapping("/{id}/resolve")
    public Result<Void> resolveAlarm(
            @PathVariable Long id,
            @Valid @RequestBody ResolveAlarmRequest request) {
        alarmService.resolveAlarm(id, request.getResolvedBy(), request.getNotes());
        return Result.success();
    }

    /** 批量处理告警 */
    @PutMapping("/batch-resolve")
    public Result<Map<String, Object>> batchResolve(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> rawIds = (List<Number>) body.get("ids");
        List<Long> ids = rawIds.stream().map(Number::longValue).toList();
        String resolvedBy = (String) body.getOrDefault("resolvedBy", "admin");
        String notes = (String) body.getOrDefault("notes", null);
        return Result.success(alarmService.batchResolve(ids, resolvedBy, notes));
    }

    /** 修改告警处理人 */
    @PutMapping("/{id}/resolvedBy")
    public Result<Void> updateResolvedBy(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        alarmService.updateResolvedBy(id, body.get("resolvedBy"));
        return Result.success();
    }

    /** 待处理告警数量 */
    @GetMapping("/pending-count")
    public Result<Map<String, Object>> getPendingCount() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pendingCount", alarmService.countPendingAlarms());
        return Result.success(result);
    }
}
