package com.streetlight.controller;

import com.streetlight.entity.AlarmLog;
import com.streetlight.service.AlarmService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/alarms")
@RequiredArgsConstructor
public class AlarmController {

    private final AlarmService alarmService;

    @GetMapping
    public ResponseEntity<Page<AlarmLog>> getAlarms(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(alarmService.getAlarms(status, type, page, size));
    }

    @PutMapping("/{id}/resolve")
    public ResponseEntity<AlarmLog> resolveAlarm(
            @PathVariable Long id, @Valid @RequestBody ResolveRequest request) {
        AlarmLog resolved = alarmService.resolveAlarm(id, request.resolvedBy());
        return ResponseEntity.ok(resolved);
    }

    public record ResolveRequest(
            @NotBlank(message = "处理人不能为空") String resolvedBy) {}
}
