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
     * 将已有传感器绑定到该设备，并通过 MQTT 通知模拟器（使用模拟器内部 sensorId）。
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

        // 通过 MQTT 通知模拟器（用 simulatorSensorId，模拟器只认识自己的内部 ID）
        Long simSensorId = sensor.getSimulatorSensorId() != null ? sensor.getSimulatorSensorId() : sensorId;
        mqttPublishService.publishBindingConfig("bind_to_device", device.getDeviceId(), simSensorId);

        log.info("设备绑定传感器 - deviceId: {}, sensorId: {}, simulatorSensorId: {}",
                device.getDeviceId(), sensorId, simSensorId);
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

        // 通过 MQTT 通知模拟器（用 simulatorSensorId，模拟器只认识自己的内部 ID）
        Long simSensorId = sensor.getSimulatorSensorId() != null ? sensor.getSimulatorSensorId() : sensorId;
        mqttPublishService.publishBindingConfig("unbind_from_device", device.getDeviceId(), simSensorId);

        log.info("设备解绑传感器 - deviceId: {}, sensorId: {}, simulatorSensorId: {}",
                device.getDeviceId(), sensorId, simSensorId);
    }

    /**
     * 通过 device_sensor 关联表反查传感器所属的设备 business key。
     * 先用模拟器 sensorId 查（MQTT topic 中的 sensorId 是模拟器内部 ID），
     * 再用 DB 主键查（兼容后端 REST API 直接传 DB id 的场景）。
     *
     * @return device_id（如 "SL-001"），未绑定时返回 sensor_{sensorId}
     */
    public String resolveDeviceIdForSensor(Long sensorId) {
        return sensorRepository.findDeviceIdBySimulatorSensorId(sensorId)
                .or(() -> sensorRepository.findDeviceIdBySensorId(sensorId))
                .orElse("sensor_" + sensorId);
    }
}
