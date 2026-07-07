package com.streetlight.controller;

import com.streetlight.entity.ControlLog;
import com.streetlight.service.ControlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/devices/{deviceId}/control")
@RequiredArgsConstructor
public class ControlController {

    private final ControlService controlService;

    @PostMapping
    public ResponseEntity<ControlLog> sendCommand(
            @PathVariable String deviceId, @Valid @RequestBody ControlRequest request) {
        ControlLog log = controlService.sendCommand(deviceId, request.command(), request.source());
        return ResponseEntity.ok(log);
    }

    @GetMapping("/logs")
    public ResponseEntity<List<ControlLog>> getLogs(@PathVariable String deviceId) {
        return ResponseEntity.ok(controlService.getControlLogs(deviceId));
    }

    public record ControlRequest(
            @NotBlank(message = "指令不能为空")
            @Pattern(regexp = "on|off", message = "指令只能是 on 或 off") String command,
            @NotBlank(message = "来源不能为空")
            @Pattern(regexp = "auto|manual", message = "来源只能是 auto 或 manual") String source) {}
}
