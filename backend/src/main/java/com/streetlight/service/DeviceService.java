package com.streetlight.service;

import com.streetlight.entity.Device;
import com.streetlight.enums.DeviceStatus;
import com.streetlight.enums.LightStatus;
import com.streetlight.enums.ControlMode;
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
        device.setStatus(DeviceStatus.OFFLINE);
        device.setLightStatus(LightStatus.OFF);
        device.setControlMode(ControlMode.AUTO);
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
    public void updateHeartbeat(String deviceId) {
        deviceRepository.findByDeviceId(deviceId).ifPresent(device -> {
            device.setLastHeartbeat(LocalDateTime.now());
            device.setStatus(DeviceStatus.ONLINE);
            deviceRepository.save(device);
        });
    }

    public List<Device> getDevicesByStatus(String status) {
        return deviceRepository.findByStatus(DeviceStatus.valueOf(status.toUpperCase()));
    }
}
