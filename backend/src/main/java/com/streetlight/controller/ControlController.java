package com.streetlight.controller;

import com.streetlight.entity.ControlLog;
import com.streetlight.service.ControlService;
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
            @PathVariable String deviceId, @RequestBody ControlRequest request) {
        ControlLog log = controlService.sendCommand(deviceId, request.command(), request.source());
        return ResponseEntity.ok(log);
    }

    @GetMapping("/logs")
    public ResponseEntity<List<ControlLog>> getLogs(@PathVariable String deviceId) {
        return ResponseEntity.ok(controlService.getControlLogs(deviceId));
    }

    public record ControlRequest(String command, String source) {}
}
