package com.streetlight.service;

import com.streetlight.entity.Device;
import com.streetlight.mqtt.MqttClientManager;
import com.streetlight.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final MqttClientManager mqttClientManager;

    public List<Device> getAllDevices() {
        return deviceRepository.findAll();
    }

    public Optional<Device> getDeviceById(Long id) {
        return deviceRepository.findById(id);
    }

    public Optional<Device> getDeviceByDeviceId(String deviceId) {
        return deviceRepository.findByDeviceId(deviceId);
    }

    @Transactional
    public Device addDevice(Device device) {
        device.setStatus("offline");
        device.setLightStatus("off");
        device.setControlMode("auto");
        Device saved = deviceRepository.save(device);
        // 订阅该设备的MQTT主题
        mqttClientManager.subscribeDevice(device.getDeviceId());
        log.info("设备添加成功 - deviceId: {}", device.getDeviceId());
        return saved;
    }

    @Transactional
    public Device updateDevice(Long id, Device updated) {
        return deviceRepository.findById(id).map(device -> {
            device.setName(updated.getName());
            device.setLocation(updated.getLocation());
            return deviceRepository.save(device);
        }).orElseThrow(() -> new RuntimeException("设备不存在, id=" + id));
    }

    @Transactional
    public void deleteDevice(Long id) {
        deviceRepository.findById(id).ifPresent(device -> {
            // 先取消订阅MQTT主题
            mqttClientManager.unsubscribeDevice(device.getDeviceId());
            // 再删除数据库记录
            deviceRepository.deleteById(id);
            log.info("设备删除成功 - deviceId: {}", device.getDeviceId());
        });
    }

    @Transactional
    public void updateHeartbeat(String deviceId) {
        deviceRepository.findByDeviceId(deviceId).ifPresent(device -> {
            device.setLastHeartbeat(LocalDateTime.now());
            device.setStatus("online");
            deviceRepository.save(device);
        });
    }

    /**
     * 如果设备不存在则自动创建（从MQTT消息中自动注册）
     */
    @Transactional
    public Device addDeviceIfNotExists(String deviceId) {
        return deviceRepository.findByDeviceId(deviceId).orElseGet(() -> {
            Device device = Device.builder()
                    .deviceId(deviceId)
                    .name(deviceId)
                    .status("online")
                    .lightStatus("off")
                    .controlMode("auto")
                    .thresholdOn(50.0)
                    .thresholdOff(100.0)
                    .location("")
                    .build();
            Device saved = deviceRepository.save(device);
            // 订阅该设备的MQTT主题
            mqttClientManager.subscribeDevice(deviceId);
            log.info("MQTT消息自动注册设备 - deviceId: {}", deviceId);
            return saved;
        });
    }

    public List<Device> getDevicesByStatus(String status) {
        return deviceRepository.findByStatus(status.toLowerCase());
    }
}
