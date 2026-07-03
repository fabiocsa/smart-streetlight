package com.streetlight.service;

import com.streetlight.entity.Device;
import com.streetlight.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceRepository deviceRepository;

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
        return deviceRepository.save(device);
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
        deviceRepository.deleteById(id);
    }

    @Transactional
    public Device updateThreshold(Long id, Double thresholdOn, Double thresholdOff) {
        return deviceRepository.findById(id).map(device -> {
            device.setThresholdOn(thresholdOn);
            device.setThresholdOff(thresholdOff);
            return deviceRepository.save(device);
        }).orElseThrow(() -> new RuntimeException("设备不存在, id=" + id));
    }

    @Transactional
    public Device updateControlMode(Long id, String controlMode) {
        return deviceRepository.findById(id).map(device -> {
            device.setControlMode(controlMode);
            return deviceRepository.save(device);
        }).orElseThrow(() -> new RuntimeException("设备不存在, id=" + id));
    }

    @Transactional
    public void updateHeartbeat(String deviceId) {
        deviceRepository.findByDeviceId(deviceId).ifPresent(device -> {
            device.setLastHeartbeat(LocalDateTime.now());
            device.setStatus("online");
            deviceRepository.save(device);
        });
    }

    public List<Device> getDevicesByStatus(String status) {
        return deviceRepository.findByStatus(status);
    }

    @Transactional
    public void updateLightStatus(String deviceId, String lightStatus) {
        deviceRepository.findByDeviceId(deviceId).ifPresent(device -> {
            device.setLightStatus(lightStatus);
            deviceRepository.save(device);
        });
    }
}
