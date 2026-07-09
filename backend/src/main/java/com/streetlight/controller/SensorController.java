package com.streetlight.controller;

import com.streetlight.common.Result;
import com.streetlight.dto.SensorFrequencyRequest;
import com.streetlight.dto.SensorRequest;
import com.streetlight.dto.SensorUpdateRequest;
import com.streetlight.entity.Sensor;
import com.streetlight.service.SensorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/devices/{deviceId}/sensors")
@RequiredArgsConstructor
public class SensorController {

    private final SensorService sensorService;

    /**
     * 获取设备的所有传感器
     */
    @GetMapping
    public ResponseEntity<List<Sensor>> getSensors(@PathVariable String deviceId) {
        return ResponseEntity.ok(sensorService.getSensorsByDeviceId(deviceId));
    }

    /**
     * 获取单个传感器
     */
    @GetMapping("/{id}")
    public ResponseEntity<Sensor> getSensor(@PathVariable String deviceId,
                                            @PathVariable Long id) {
        return sensorService.getSensorById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 绑定传感器到设备
     */
    @PostMapping
    public ResponseEntity<Sensor> addSensor(@PathVariable String deviceId,
                                            @Valid @RequestBody SensorRequest request) {
        Sensor created = sensorService.addSensor(deviceId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * 更新传感器配置
     */
    @PutMapping("/{id}")
    public ResponseEntity<Sensor> updateSensor(@PathVariable String deviceId,
                                               @PathVariable Long id,
                                               @Valid @RequestBody SensorUpdateRequest request) {
        Sensor updated = sensorService.updateSensor(id, request);
        return ResponseEntity.ok(updated);
    }

    /**
     * 解绑传感器
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSensor(@PathVariable String deviceId,
                                             @PathVariable Long id) {
        sensorService.deleteSensor(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 调整传感器上报频率
     */
    @PutMapping("/{id}/frequency")
    public ResponseEntity<Sensor> updateFrequency(@PathVariable String deviceId,
                                                  @PathVariable Long id,
                                                  @Valid @RequestBody SensorFrequencyRequest request) {
        Sensor updated = sensorService.updateFrequency(id, request);
        return ResponseEntity.ok(updated);
    }

    /**
     * 同步传感器配置到模拟器
     */
    @PostMapping("/sync-to-mock")
    public Result<Map<String, Object>> syncToMock(@PathVariable String deviceId) {
        int count = sensorService.syncToMock(deviceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "传感器配置已同步到模拟器");
        result.put("syncedCount", count);
        return Result.success(result);
    }

    /**
     * 解绑传感器（设 device_id = NULL，不删除记录）
     */
    @PostMapping("/{id}/unbind")
    public ResponseEntity<Sensor> unbindSensor(@PathVariable String deviceId,
                                                @PathVariable Long id) {
        Sensor sensor = sensorService.unbindSensor(id);
        return ResponseEntity.ok(sensor);
    }

    /**
     * 传感器换绑到另一设备
     */
    @PostMapping("/{id}/rebind")
    public ResponseEntity<Sensor> rebindSensor(@PathVariable String deviceId,
                                                @PathVariable Long id,
                                                @RequestBody Map<String, String> body) {
        String newDeviceId = body.get("deviceId");
        if (newDeviceId == null || newDeviceId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Sensor sensor = sensorService.rebindSensor(id, newDeviceId);
        return ResponseEntity.ok(sensor);
    }
}
