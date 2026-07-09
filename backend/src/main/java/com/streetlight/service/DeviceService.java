package com.streetlight.service;

import com.streetlight.common.BusinessException;
import com.streetlight.entity.Device;
import com.streetlight.entity.Sensor;
import com.streetlight.mqtt.MqttClientManager;
import com.streetlight.mqtt.MqttPublishService;
import com.streetlight.repository.DeviceRepository;
import com.streetlight.repository.SensorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final SensorRepository sensorRepository;
    private final MqttClientManager mqttClientManager;
    private final MqttPublishService mqttPublishService;

    public List<Device> getAllDevices() {
        return deviceRepository.findAll();
    }

    public Optional<Device> getDeviceById(Long id) {
        return deviceRepository.findById(id);
    }

    public Optional<Device> getDeviceByDeviceId(String deviceId) {
        return deviceRepository.findByDeviceId(deviceId);
    }

    /**
     * 获取设备详情（含已绑定传感器列表）。
     */
    public Optional<Device> getDeviceWithSensors(Long id) {
        return deviceRepository.findByIdWithSensors(id);
    }

    @Transactional
    public Device addDevice(Device device) {
        device.setStatus("offline");
        device.setLightStatus("off");
        device.setControlMode("auto");
        Device saved = deviceRepository.save(device);
        // 订阅该设备的 MQTT 控制主题
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
            // 先取消订阅 MQTT 主题
            mqttClientManager.unsubscribeDevice(device.getDeviceId());
            // 再删除数据库记录（级联删除 device_sensor 关联）
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
     * 如果设备不存在则自动创建（从 MQTT 消息中自动注册）。
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
            mqttClientManager.subscribeDevice(deviceId);
            log.info("MQTT 消息自动注册设备 - deviceId: {}", deviceId);
            return saved;
        });
    }

    public List<Device> getDevicesByStatus(String status) {
        return deviceRepository.findByStatus(status.toLowerCase());
    }

    // ======================== 设备-传感器绑定 ========================

    /**
     * 获取设备已绑定的传感器列表。
     */
    public List<Sensor> getDeviceSensors(Long deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new BusinessException("设备不存在, id=" + deviceId));
        return new ArrayList<>(device.getSensors());
    }

    /**
     * 设备绑定传感器。
     * 将已有传感器绑定到该设备，并通过 MQTT 通知模拟器。
     */
    @Transactional
    public void bindSensor(Long deviceId, Long sensorId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new BusinessException("设备不存在, id=" + deviceId));
        Sensor sensor = sensorRepository.findById(sensorId)
                .orElseThrow(() -> new BusinessException("传感器不存在, id=" + sensorId));

        if (device.getSensors().contains(sensor)) {
            throw new BusinessException("传感器已绑定到此设备");
        }

        device.getSensors().add(sensor);
        deviceRepository.save(device);

        // 通过 MQTT 通知模拟器传感器已绑定到设备
        mqttPublishService.publishBindingConfig("bind_to_device", device.getDeviceId(), sensorId);

        log.info("设备绑定传感器 - deviceId: {}, sensorId: {}", device.getDeviceId(), sensorId);
    }

    /**
     * 设备解绑传感器（不删除传感器，仅移除绑定关系）。
     */
    @Transactional
    public void unbindSensor(Long deviceId, Long sensorId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new BusinessException("设备不存在, id=" + deviceId));
        Sensor sensor = sensorRepository.findById(sensorId)
                .orElseThrow(() -> new BusinessException("传感器不存在, id=" + sensorId));

        device.getSensors().removeIf(s -> s.getId().equals(sensorId));
        deviceRepository.save(device);

        // 通过 MQTT 通知模拟器传感器已解绑
        mqttPublishService.publishBindingConfig("unbind_from_device", device.getDeviceId(), sensorId);

        log.info("设备解绑传感器 - deviceId: {}, sensorId: {}", device.getDeviceId(), sensorId);
    }

    /**
     * 通过 device_sensor 关联表反查传感器所属的设备 business key。
     *
     * @return device_id（如 "SL-001"），未绑定时返回 sensor_{sensorId}
     */
    public String resolveDeviceIdForSensor(Long sensorId) {
        Optional<Sensor> sensorOpt = sensorRepository.findById(sensorId);
        if (sensorOpt.isEmpty()) {
            return "unknown";
        }
        // 查找绑定此传感器的设备
        List<Device> devices = deviceRepository.findAll();
        for (Device device : devices) {
            if (device.getSensors().stream().anyMatch(s -> s.getId().equals(sensorId))) {
                return device.getDeviceId();
            }
        }
        return "sensor_" + sensorId; // fallback: 未绑定
    }
}
