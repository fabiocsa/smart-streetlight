package com.streetlight.service.impl;

import com.streetlight.common.BusinessException;
import com.streetlight.dto.SensorFrequencyRequest;
import com.streetlight.dto.SensorRequest;
import com.streetlight.dto.SensorUpdateRequest;
import com.streetlight.entity.Sensor;
import com.streetlight.repository.SensorRepository;
import com.streetlight.service.SensorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SensorServiceImpl implements SensorService {

    private final SensorRepository sensorRepository;

    @Override
    public List<Sensor> getAllSensors() {
        List<Sensor> sensors = sensorRepository.findAll();
        enrichWithBinding(sensors);
        return sensors;
    }

    @Override
    public List<Sensor> getUnboundSensors() {
        return sensorRepository.findUnboundSensors();
    }

    /**
     * 批量填充传感器的 boundDeviceId（一次 JPQL 查询，避免 N+1）。
     */
    private void enrichWithBinding(List<Sensor> sensors) {
        if (sensors.isEmpty()) return;
        Map<Long, String> bindingMap = sensorRepository.findAllSensorDeviceBindings().stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (String) row[1],
                        (a, b) -> a));  // 一个传感器只绑定一个设备
        for (Sensor s : sensors) {
            s.setBoundDeviceId(bindingMap.get(s.getId()));
        }
    }

    @Override
    public Optional<Sensor> getSensorById(Long id) {
        return sensorRepository.findById(id);
    }

    @Override
    @Transactional
    public Sensor createSensor(SensorRequest request) {
        Sensor sensor = Sensor.builder()
                .sensorType(request.getSensorType())
                .displayName(request.getDisplayName())
                .dataTopic(request.getDataTopic())
                .reportFrequency(request.getReportFrequency())
                .enabled(true)
                .configJson(request.getConfigJson())
                .build();

        Sensor saved = sensorRepository.save(sensor);
        log.info("传感器创建成功 - sensorId: {}, type: {}", saved.getId(), request.getSensorType());

        // 仅写入数据库，不通知模拟器（前后端与模拟器隔离）
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
        log.info("传感器配置更新 - sensorId: {}", id);

        // 仅写入数据库，不通知模拟器（前后端与模拟器隔离）
        return saved;
    }

    @Override
    @Transactional
    public void deleteSensor(Long id) {
        Sensor sensor = sensorRepository.findById(id)
                .orElseThrow(() -> new BusinessException("传感器不存在, id=" + id));

        // 硬删除：从数据库移除记录。不通知模拟器（前后端与模拟器隔离）。
        // 模拟器仍可继续发送数据，后端在 handleSensorData 中会自动重新注册。
        sensorRepository.delete(sensor);
        log.info("传感器已删除（模拟器仍在运行时会自动重新识别） - sensorId: {}, simulatorSensorId: {}",
                id, sensor.getSimulatorSensorId());
    }

    @Override
    @Transactional
    public Sensor updateFrequency(Long id, SensorFrequencyRequest request) {
        Sensor sensor = sensorRepository.findById(id)
                .orElseThrow(() -> new BusinessException("传感器不存在, id=" + id));

        sensor.setReportFrequency(request.getReportFrequency());
        Sensor saved = sensorRepository.save(sensor);
        log.info("传感器频率更新 - sensorId: {}, frequency: {}", id, request.getReportFrequency());

        // 仅写入数据库，不通知模拟器（前后端与模拟器隔离）
        return saved;
    }

    @Override
    @Transactional
    public int syncToMock(Long sensorId) {
        // 前后端与模拟器隔离，不再主动同步到模拟器。
        // 模拟器自行通过 MQTT 注册传感器，后端自动发现。
        log.info("syncToMock 已废弃 - sensorId: {}（前后端与模拟器隔离，无需同步）", sensorId);
        return 1;
    }
}
