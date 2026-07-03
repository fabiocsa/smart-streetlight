package com.streetlight.controller;

import com.streetlight.entity.Device;
import com.streetlight.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @GetMapping
    public ResponseEntity<List<Device>> getAllDevices() {
        return ResponseEntity.ok(deviceService.getAllDevices());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Device> getDeviceById(@PathVariable Long id) {
        return deviceService.getDeviceById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Device> addDevice(@RequestBody Device device) {
        Device created = deviceService.addDevice(device);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Device> updateDevice(@PathVariable Long id, @RequestBody Device device) {
        Device updated = deviceService.updateDevice(id, device);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDevice(@PathVariable Long id) {
        deviceService.deleteDevice(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/control-mode")
    public ResponseEntity<Device> setControlMode(
            @PathVariable Long id, @RequestBody ControlModeRequest request) {
        Device updated = deviceService.updateControlMode(id, request.controlMode());
        return ResponseEntity.ok(updated);
    }

    @PutMapping("/{id}/threshold")
    public ResponseEntity<Device> setThreshold(
            @PathVariable Long id, @RequestBody ThresholdRequest request) {
        Device updated = deviceService.updateThreshold(id, request.thresholdOn(), request.thresholdOff());
        return ResponseEntity.ok(updated);
    }

    public record ControlModeRequest(String controlMode) {}
    public record ThresholdRequest(Double thresholdOn, Double thresholdOff) {}
}
