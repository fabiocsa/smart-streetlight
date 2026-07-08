package com.streetlight.controller;

import com.streetlight.common.Result;
import com.streetlight.entity.AlarmRule;
import com.streetlight.service.AlarmRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alarm-rules")
@RequiredArgsConstructor
public class AlarmRuleController {

    private final AlarmRuleService alarmRuleService;

    @GetMapping
    public Result<List<AlarmRule>> getAllRules() {
        return Result.success(alarmRuleService.getAllRules());
    }

    @GetMapping("/{id}")
    public Result<AlarmRule> getRule(@PathVariable Long id) {
        return Result.success(alarmRuleService.getRule(id));
    }

    @PostMapping
    public Result<AlarmRule> createRule(@Valid @RequestBody AlarmRule rule) {
        return Result.success(alarmRuleService.createRule(rule));
    }

    @PutMapping("/{id}")
    public Result<AlarmRule> updateRule(@PathVariable Long id, @Valid @RequestBody AlarmRule rule) {
        return Result.success(alarmRuleService.updateRule(id, rule));
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteRule(@PathVariable Long id) {
        alarmRuleService.deleteRule(id);
        return Result.success();
    }

    @PutMapping("/{id}/toggle")
    public Result<AlarmRule> toggleRule(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        boolean enabled = body.getOrDefault("enabled", true);
        return Result.success(alarmRuleService.toggleRule(id, enabled));
    }
}
