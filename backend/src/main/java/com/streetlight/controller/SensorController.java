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

/**
 * 传感器管理控制器 (v2)
 * 传感器独立路由，不再嵌套在设备路径下。
 * 绑定/解绑操作在 DeviceController 中。
 */
@RestController
@RequestMapping("/api/sensors")
@RequiredArgsConstructor
public class SensorController {

    private final SensorService sensorService;

    /** 获取所有传感器 */
    @GetMapping
    public ResponseEntity<List<Sensor>> getAllSensors() {
        return ResponseEntity.ok(sensorService.getAllSensors());
    }

    /** 获取未绑定传感器列表 */
    @GetMapping("/unbound")
    public ResponseEntity<List<Sensor>> getUnboundSensors() {
        return ResponseEntity.ok(sensorService.getUnboundSensors());
    }

    /** 获取单个传感器 */
    @GetMapping("/{id}")
    public ResponseEntity<Sensor> getSensor(@PathVariable Long id) {
        return sensorService.getSensorById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** 创建传感器 */
    @PostMapping
    public ResponseEntity<Sensor> createSensor(@Valid @RequestBody SensorRequest request) {
        Sensor created = sensorService.createSensor(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** 更新传感器配置 */
    @PutMapping("/{id}")
    public ResponseEntity<Sensor> updateSensor(@PathVariable Long id,
                                               @Valid @RequestBody SensorUpdateRequest request) {
        Sensor updated = sensorService.updateSensor(id, request);
        return ResponseEntity.ok(updated);
    }

    /** 删除传感器 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSensor(@PathVariable Long id) {
        sensorService.deleteSensor(id);
        return ResponseEntity.noContent().build();
    }

    /** 调整传感器上报频率 */
    @PutMapping("/{id}/frequency")
    public ResponseEntity<Sensor> updateFrequency(@PathVariable Long id,
                                                  @Valid @RequestBody SensorFrequencyRequest request) {
        Sensor updated = sensorService.updateFrequency(id, request);
        return ResponseEntity.ok(updated);
    }

    /** 同步传感器配置到模拟器 */
    @PostMapping("/{id}/sync-to-mock")
    public Result<Map<String, Object>> syncToMock(@PathVariable Long id) {
        int count = sensorService.syncToMock(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "传感器配置已同步到模拟器");
        result.put("syncedCount", count);
        return Result.success(result);
    }
}
