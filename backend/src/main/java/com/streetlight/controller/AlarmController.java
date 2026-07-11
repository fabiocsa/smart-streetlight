package com.streetlight.controller;

import com.streetlight.common.Result;
import com.streetlight.dto.ResolveAlarmRequest;
import com.streetlight.entity.AlarmLog;
import com.streetlight.repository.SystemConfigRepository;
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
    private final SystemConfigRepository systemConfigRepository;

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

    /** 批量修改告警处理人 */
    @PutMapping("/batch-resolvedBy")
    public Result<Map<String, Object>> batchUpdateResolvedBy(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> rawIds = (List<Number>) body.get("ids");
        List<Long> ids = rawIds.stream().map(Number::longValue).toList();
        String resolvedBy = (String) body.getOrDefault("resolvedBy", "admin");
        return Result.success(alarmService.batchUpdateResolvedBy(ids, resolvedBy));
    }

    /** 待处理告警数量 */
    @GetMapping("/pending-count")
    public Result<Map<String, Object>> getPendingCount() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pendingCount", alarmService.countPendingAlarms());
        return Result.success(result);
    }

    /** 手动分配处理人并解决告警 */
    @PutMapping("/{alarmId}/assign/{handlerId}")
    public Result<Void> assignHandler(
            @PathVariable Long alarmId,
            @PathVariable Long handlerId) {
        alarmService.assignHandler(alarmId, handlerId);
        return Result.success();
    }

    /** 手动触发：对所有 PENDING 告警自动分配处理人 */
    @PostMapping("/batch-auto-assign")
    public Result<Map<String, Object>> batchAutoAssign() {
        return Result.success(alarmService.batchAutoAssignPending());
    }

    // ==================== 电压配置 ====================

    /** 获取电压正常区间 */
    @GetMapping("/voltage-config")
    public Result<Map<String, Object>> getVoltageConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("min", getConfigDouble("voltage_min", 210.0));
        result.put("max", getConfigDouble("voltage_max", 240.0));
        return Result.success(result);
    }

    /** 设置电压正常区间 */
    @PutMapping("/voltage-config")
    public Result<Void> setVoltageConfig(@RequestBody Map<String, Object> body) {
        if (body.containsKey("min")) {
            Double min = body.get("min") instanceof Number ? ((Number) body.get("min")).doubleValue() : null;
            if (min != null) {
                saveOrUpdateConfig("voltage_min", String.valueOf(min));
            }
        }
        if (body.containsKey("max")) {
            Double max = body.get("max") instanceof Number ? ((Number) body.get("max")).doubleValue() : null;
            if (max != null) {
                saveOrUpdateConfig("voltage_max", String.valueOf(max));
            }
        }
        return Result.success();
    }

    // ==================== 温度配置 ====================

    /** 获取温度上限 */
    @GetMapping("/temperature-config")
    public Result<Map<String, Object>> getTemperatureConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("max", getConfigDouble("temperature_max", 45.0));
        return Result.success(result);
    }

    /** 设置温度上限 */
    @PutMapping("/temperature-config")
    public Result<Void> setTemperatureConfig(@RequestBody Map<String, Object> body) {
        if (body.containsKey("max")) {
            Double max = body.get("max") instanceof Number ? ((Number) body.get("max")).doubleValue() : null;
            if (max != null) {
                saveOrUpdateConfig("temperature_max", String.valueOf(max));
            }
        }
        return Result.success();
    }

    // ==================== 功率配置 ====================

    /** 获取功率上限 */
    @GetMapping("/power-config")
    public Result<Map<String, Object>> getPowerConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("max", getConfigDouble("power_max", 100.0));
        return Result.success(result);
    }

    /** 设置功率上限 */
    @PutMapping("/power-config")
    public Result<Void> setPowerConfig(@RequestBody Map<String, Object> body) {
        if (body.containsKey("max")) {
            Double max = body.get("max") instanceof Number ? ((Number) body.get("max")).doubleValue() : null;
            if (max != null) {
                saveOrUpdateConfig("power_max", String.valueOf(max));
            }
        }
        return Result.success();
    }

    private Double getConfigDouble(String key, Double defaultValue) {
        return systemConfigRepository.findByConfigKey(key)
                .map(c -> {
                    try { return Double.parseDouble(c.getConfigValue()); }
                    catch (NumberFormatException e) { return defaultValue; }
                })
                .orElse(defaultValue);
    }

    private void saveOrUpdateConfig(String key, String value) {
        com.streetlight.entity.SystemConfig config = systemConfigRepository.findByConfigKey(key)
                .orElse(com.streetlight.entity.SystemConfig.builder()
                        .configKey(key)
                        .configValue(value)
                        .build());
        config.setConfigValue(value);
        systemConfigRepository.save(config);
    }
}
