package com.streetlight.service;

import com.streetlight.entity.Device;
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
        log.info("设备已添加 - id: {}, deviceId: {}, name: {}, location: {}",
                saved.getId(), saved.getDeviceId(), saved.getName(), saved.getLocation());
        return saved;
    }

    @Transactional
    public Device updateDevice(Long id, Device updated) {
        return deviceRepository.findById(id).map(device -> {
            device.setName(updated.getName());
            device.setLocation(updated.getLocation());
            Device saved = deviceRepository.save(device);
            log.info("设备已更新 - id: {}, name: {}, location: {}", id, saved.getName(), saved.getLocation());
            return saved;
        }).orElseThrow(() -> {
            log.error("更新失败，设备不存在 - id: {}", id);
            return new RuntimeException("设备不存在, id=" + id);
        });
    }

    @Transactional
    public void deleteDevice(Long id) {
        deviceRepository.deleteById(id);
        log.info("设备已删除 - id: {}", id);
    }

    @Transactional
    public Device updateThreshold(Long id, Double thresholdOn, Double thresholdOff) {
        return deviceRepository.findById(id).map(device -> {
            device.setThresholdOn(thresholdOn);
            device.setThresholdOff(thresholdOff);
            Device saved = deviceRepository.save(device);
            log.info("设备阈值已更新 - id: {}, thresholdOn: {}, thresholdOff: {}", id, thresholdOn, thresholdOff);
            return saved;
        }).orElseThrow(() -> {
            log.error("更新阈值失败，设备不存在 - id: {}", id);
            return new RuntimeException("设备不存在, id=" + id);
        });
    }

    @Transactional
    public Device updateControlMode(Long id, String controlMode) {
        return deviceRepository.findById(id).map(device -> {
            device.setControlMode(controlMode);
            Device saved = deviceRepository.save(device);
            log.info("设备控制模式已更新 - id: {}, controlMode: {}", id, controlMode);
            return saved;
        }).orElseThrow(() -> {
            log.error("更新控制模式失败，设备不存在 - id: {}", id);
            return new RuntimeException("设备不存在, id=" + id);
        });
    }

    @Transactional
    public void updateHeartbeat(String deviceId) {
        deviceRepository.findByDeviceId(deviceId).ifPresentOrElse(device -> {
            device.setLastHeartbeat(LocalDateTime.now());
            device.setStatus("online");
            deviceRepository.save(device);
            log.debug("设备心跳已更新 - deviceId: {}", deviceId);
        }, () -> log.warn("心跳更新失败，设备不存在 - deviceId: {}", deviceId));
    }

    public List<Device> getDevicesByStatus(String status) {
        List<Device> devices = deviceRepository.findByStatus(status);
        log.debug("查询设备列表 - status: {}, count: {}", status, devices.size());
        return devices;
    }

    @Transactional
    public void updateLightStatus(String deviceId, String lightStatus) {
        deviceRepository.findByDeviceId(deviceId).ifPresentOrElse(device -> {
            device.setLightStatus(lightStatus);
            deviceRepository.save(device);
            log.debug("设备灯光状态已更新 - deviceId: {}, lightStatus: {}", deviceId, lightStatus);
        }, () -> log.warn("更新灯光状态失败，设备不存在 - deviceId: {}", deviceId));
    }
}
