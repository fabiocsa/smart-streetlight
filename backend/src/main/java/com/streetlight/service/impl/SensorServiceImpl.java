package com.streetlight.service.impl;

import com.streetlight.common.BusinessException;
import com.streetlight.dto.SensorFrequencyRequest;
import com.streetlight.dto.SensorRequest;
import com.streetlight.dto.SensorUpdateRequest;
import com.streetlight.entity.Device;
import com.streetlight.entity.Sensor;
import com.streetlight.mqtt.MqttPublishService;
import com.streetlight.repository.DeviceRepository;
import com.streetlight.repository.SensorRepository;
import com.streetlight.service.SensorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SensorServiceImpl implements SensorService {

    private final SensorRepository sensorRepository;
    private final DeviceRepository deviceRepository;
    private final MqttPublishService mqttPublishService;

    @Override
    public List<Sensor> getSensorsByDeviceId(String deviceId) {
        // 验证设备存在
        deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new BusinessException("设备不存在, deviceId=" + deviceId));
        return sensorRepository.findByDeviceId(deviceId);
    }

    @Override
    public Optional<Sensor> getSensorById(Long id) {
        return sensorRepository.findById(id);
    }

    @Override
    @Transactional
    public Sensor addSensor(String deviceId, SensorRequest request) {
        // 允许 deviceId 为空（无主传感器），此时跳过设备存在校验
        if (deviceId != null && !deviceId.isBlank()) {
            Device device = deviceRepository.findByDeviceId(deviceId)
                    .orElseThrow(() -> new BusinessException("设备不存在, deviceId=" + deviceId));
        }

        Sensor sensor = Sensor.builder()
                .deviceId(deviceId)
                .sensorType(request.getSensorType())
                .displayName(request.getDisplayName())
                .dataTopic(request.getDataTopic())
                .reportFrequency(request.getReportFrequency())
                .enabled(true)
                .configJson(request.getConfigJson())
                .build();

        Sensor saved = sensorRepository.save(sensor);
        log.info("传感器绑定成功 - deviceId: {}, sensorId: {}, type: {}",
                deviceId, saved.getId(), request.getSensorType());

        // 通知模拟器添加传感器
        mqttPublishService.publishSensorConfig(deviceId, "add_sensor", saved);

        return saved;
    }

    @Override
    @Transactional
    public Sensor updateSensor(Long id, SensorUpdateRequest request) {
        Sensor sensor = sensorRepository.findById(id)
                .orElseThrow(() -> new BusinessException("传感器不存在, id=" + id));

        if (request.getSensorType() != null) {
            sensor.setSensorType(request.getSensorType());
        }
        if (request.getDisplayName() != null) {
            sensor.setDisplayName(request.getDisplayName());
        }
        if (request.getDataTopic() != null) {
            sensor.setDataTopic(request.getDataTopic());
        }
        if (request.getReportFrequency() != null) {
            sensor.setReportFrequency(request.getReportFrequency());
        }
        if (request.getEnabled() != null) {
            sensor.setEnabled(request.getEnabled());
        }
        if (request.getConfigJson() != null) {
            sensor.setConfigJson(request.getConfigJson());
        }

        Sensor saved = sensorRepository.save(sensor);
        log.info("传感器配置更新 - sensorId: {}, deviceId: {}", id, sensor.getDeviceId());

        // 通知模拟器更新传感器配置
        String action = saved.getEnabled() ? "set_frequency" : "stop_sensor";
        mqttPublishService.publishSensorConfig(sensor.getDeviceId(), action, saved);

        return saved;
    }

    @Override
    @Transactional
    public void deleteSensor(Long id) {
        Sensor sensor = sensorRepository.findById(id)
                .orElseThrow(() -> new BusinessException("传感器不存在, id=" + id));

        String deviceId = sensor.getDeviceId();

        // 通知模拟器移除传感器
        mqttPublishService.publishSensorConfig(deviceId, "remove_sensor", sensor);

        sensorRepository.deleteById(id);
        log.info("传感器解绑成功 - sensorId: {}, deviceId: {}", id, deviceId);
    }

    @Override
    @Transactional
    public Sensor updateFrequency(Long id, SensorFrequencyRequest request) {
        Sensor sensor = sensorRepository.findById(id)
                .orElseThrow(() -> new BusinessException("传感器不存在, id=" + id));

        sensor.setReportFrequency(request.getReportFrequency());
        Sensor saved = sensorRepository.save(sensor);
        log.info("传感器频率更新 - sensorId: {}, frequency: {}", id, request.getReportFrequency());

        // 通知模拟器更新频率
        mqttPublishService.publishSensorConfig(sensor.getDeviceId(), "set_frequency", saved);

        return saved;
    }

    @Override
    @Transactional
    public int syncToMock(String deviceId) {
        // 验证设备存在（仅当 deviceId 非空时）
        if (deviceId != null && !deviceId.isBlank()) {
            deviceRepository.findByDeviceId(deviceId)
                    .orElseThrow(() -> new BusinessException("设备不存在, deviceId=" + deviceId));
        }

        List<Sensor> sensors;
        if (deviceId == null || deviceId.isBlank()) {
            sensors = sensorRepository.findAll();
        } else {
            sensors = sensorRepository.findByDeviceIdAndEnabled(deviceId, true);
        }
        if (sensors.isEmpty()) {
            log.warn("设备 {} 没有已启用的传感器，跳过同步", deviceId);
            return 0;
        }

        // 逐个通知模拟器
        for (Sensor sensor : sensors) {
            String did = sensor.getDeviceId() != null ? sensor.getDeviceId() : "";
            mqttPublishService.publishSensorConfig(did, "add_sensor", sensor);
        }

        log.info("传感器配置已同步到模拟器 - deviceId: {}, count: {}", deviceId, sensors.size());
        return sensors.size();
    }

    @Override
    public List<Sensor> getAllSensors() {
        return sensorRepository.findAll();
    }

    @Override
    @Transactional
    public Sensor unbindSensor(Long id) {
        Sensor sensor = sensorRepository.findById(id)
                .orElseThrow(() -> new BusinessException("传感器不存在, id=" + id));

        String oldDeviceId = sensor.getDeviceId();
        sensor.setDeviceId(null);
        Sensor saved = sensorRepository.save(sensor);
        log.info("传感器解绑 - sensorId: {}, 原设备: {}", id, oldDeviceId);

        // 通知模拟器移除传感器
        if (oldDeviceId != null) {
            mqttPublishService.publishSensorConfig(oldDeviceId, "remove_sensor", saved);
        }

        return saved;
    }

    @Override
    @Transactional
    public Sensor rebindSensor(Long id, String newDeviceId) {
        Sensor sensor = sensorRepository.findById(id)
                .orElseThrow(() -> new BusinessException("传感器不存在, id=" + id));

        // 验证新设备存在
        deviceRepository.findByDeviceId(newDeviceId)
                .orElseThrow(() -> new BusinessException("设备不存在, deviceId=" + newDeviceId));

        String oldDeviceId = sensor.getDeviceId();
        sensor.setDeviceId(newDeviceId);
        Sensor saved = sensorRepository.save(sensor);
        log.info("传感器换绑 - sensorId: {}, 旧设备: {}, 新设备: {}", id, oldDeviceId, newDeviceId);

        // 通知模拟器添加传感器到新设备
        mqttPublishService.publishSensorConfig(newDeviceId, "add_sensor", saved);

        return saved;
    }
}
