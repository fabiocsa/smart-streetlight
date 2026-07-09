package com.streetlight.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streetlight.entity.Sensor;
import com.streetlight.repository.SensorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 传感器注册服务 (v2)
 * 传感器通过 MQTT 独立注册，不携带设备 ID。
 * 注册后自动进入"未绑定"状态，由管理员在界面中手动绑定到设备。
 */
@Service
@Slf4j
public class SensorRegistrationService {

    private final SensorRepository sensorRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SensorRegistrationService(SensorRepository sensorRepository) {
        this.sensorRepository = sensorRepository;
    }

    /**
     * 处理传感器注册消息（MQTT: streetlight/sensor/register）。
     * 传感器独立注册，不关联任何设备。
     */
    @Transactional
    public void handleSensorRegister(JsonNode payload) {
        String sensorType = payload.has("sensorType")
                ? payload.get("sensorType").asText() : "light";
        String displayName = payload.has("displayName")
                ? payload.get("displayName").asText() : sensorType;
        String dataTopic = payload.has("dataTopic")
                ? payload.get("dataTopic").asText()
                : "streetlight/sensor/unknown/data";
        int reportFrequency = payload.has("reportFrequency")
                ? payload.get("reportFrequency").asInt() : 5;
        String configJson = payload.has("configJson")
                ? payload.get("configJson").asText() : null;

        // 从 payload 提取模拟器内部 sensorId → 映射到 simulatorSensorId 字段
        Long simulatorSensorId = null;
        if (payload.has("sensorId")) {
            simulatorSensorId = payload.get("sensorId").asLong();
        }

        Sensor sensor;
        if (simulatorSensorId != null) {
            Optional<Sensor> existing = sensorRepository.findBySimulatorSensorId(simulatorSensorId);
            if (existing.isPresent()) {
                // 已存在则更新（同一模拟器传感器重启后重新注册，不创建重复记录）
                sensor = existing.get();
                sensor.setSensorType(sensorType);
                sensor.setDisplayName(displayName);
                sensor.setDataTopic(dataTopic);
                sensor.setReportFrequency(reportFrequency);
                sensor.setEnabled(true);
                if (configJson != null) sensor.setConfigJson(configJson);
                sensor = sensorRepository.save(sensor);
                log.info("传感器已更新: id={}, simulatorSensorId={}, type={}", sensor.getId(), simulatorSensorId, sensorType);
                return;
            }
        }

        // 新建传感器（未绑定状态），记录 simulatorSensorId 用于后续去重
        sensor = Sensor.builder()
                .sensorType(sensorType)
                .displayName(displayName)
                .dataTopic(dataTopic)
                .reportFrequency(reportFrequency)
                .enabled(true)
                .configJson(configJson)
                .simulatorSensorId(simulatorSensorId)
                .build();
        sensor = sensorRepository.save(sensor);
        log.info("传感器已注册（未绑定）: id={}, simulatorSensorId={}, type={}", sensor.getId(), simulatorSensorId, sensorType);
    }

    /**
     * 处理传感器注销（MQTT: streetlight/sensor/unregister）。
     * sensorId 为模拟器内部 ID，需通过 simulatorSensorId 匹配而非 DB 主键。
     */
    @Transactional
    public void handleSensorUnregister(Long sensorId) {
        sensorRepository.findBySimulatorSensorId(sensorId).ifPresent(sensor -> {
            sensor.setEnabled(false);
            sensorRepository.save(sensor);
            log.info("传感器已注销: id={}, simulatorSensorId={}", sensor.getId(), sensorId);
        });
    }
}
