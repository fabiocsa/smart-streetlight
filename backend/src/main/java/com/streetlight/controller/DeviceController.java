package com.streetlight.controller;

import com.streetlight.entity.Device;
import com.streetlight.entity.Sensor;
import com.streetlight.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @GetMapping
    public ResponseEntity<List<Device>> getAllDevices() {
        return ResponseEntity.ok(deviceService.getAllDevices());
    }

    /**
     * 获取设备详情。支持数字 DB id 和业务键（如 "SL-001"）两种查找方式。
     * 告警等模块持有的是业务键 deviceId，前端路由也使用业务键。
     */
    @GetMapping("/{id}")
    public ResponseEntity<Device> getDeviceById(@PathVariable String id) {
        // 先尝试按数字 DB id 查找
        try {
            Long numericId = Long.parseLong(id);
            return deviceService.getDeviceById(numericId)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (NumberFormatException e) {
            // 非数字 → 按业务键查找（如 "SL-001"）
            return deviceService.getDeviceByDeviceId(id)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        }
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

    // ======================== 设备-传感器绑定 ========================

    /** 获取设备已绑定的传感器列表 */
    @GetMapping("/{id}/sensors")
    public ResponseEntity<List<Sensor>> getDeviceSensors(@PathVariable Long id) {
        return ResponseEntity.ok(deviceService.getDeviceSensors(id));
    }

    /** 设备绑定传感器 */
    @PostMapping("/{id}/bind-sensor")
    public ResponseEntity<?> bindSensor(@PathVariable Long id, @RequestBody Map<String, Long> body) {
        Long sensorId = body.get("sensorId");
        if (sensorId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "sensorId 不能为空"));
        }
        deviceService.bindSensor(id, sensorId);
        return ResponseEntity.ok(Map.of("message", "传感器绑定成功"));
    }

    /** 设备解绑传感器 */
    @PostMapping("/{id}/unbind-sensor/{sensorId}")
    public ResponseEntity<?> unbindSensor(@PathVariable Long id, @PathVariable Long sensorId) {
        deviceService.unbindSensor(id, sensorId);
        return ResponseEntity.ok(Map.of("message", "传感器已移除绑定"));
    }
}
