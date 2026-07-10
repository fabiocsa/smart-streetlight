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
        int reportFrequency = payload.has("reportFrequency")
                ? payload.get("reportFrequency").asInt() : 5;
        String configJson = payload.has("configJson")
                ? payload.get("configJson").asText() : null;

        // 从 payload 提取模拟器内部 sensorId → 映射到 simulatorSensorId 字段
        Long simulatorSensorId = null;
        if (payload.has("sensorId")) {
            simulatorSensorId = payload.get("sensorId").asLong();
        }

        // ★ dataTopic 必须与 sensorId 一致：从 sensorId 派生，忽略 payload 中可能不匹配的值
        String dataTopic = simulatorSensorId != null
                ? "streetlight/sensor/" + simulatorSensorId + "/data"
                : (payload.has("dataTopic") ? payload.get("dataTopic").asText() : "streetlight/sensor/unknown/data");

        // 如果 payload 中的 dataTopic 与派生值不一致，记录警告
        if (payload.has("dataTopic") && simulatorSensorId != null) {
            String payloadTopic = payload.get("dataTopic").asText();
            if (!dataTopic.equals(payloadTopic)) {
                log.warn("传感器注册 dataTopic 不一致，已自动修正: payload topic={}, 派生 topic={}, sensorId={}",
                        payloadTopic, dataTopic, simulatorSensorId);
            }
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

    /**
     * 从传感器数据中自动注册/恢复传感器（新事务，独立于数据保存）。
     * 用于 MQTT 数据消息到达时，发现传感器尚未注册的容错恢复场景。
     * dataTopic 始终从 simulatorSensorId 派生，保证与注册消息一致。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void autoRegisterFromData(Long simulatorSensorId, String sensorType,
                                     String displayName, String dataTopic) {
        // ★ 强制 dataTopic 与 simulatorSensorId 一致，防止因 topic 不匹配产生重复传感器
        String correctTopic = "streetlight/sensor/" + simulatorSensorId + "/data";
        if (!correctTopic.equals(dataTopic)) {
            log.warn("autoRegisterFromData: dataTopic 与 sensorId 不一致，已自动修正: "
                    + "传入 topic={}, 修正 topic={}, sensorId={}", dataTopic, correctTopic, simulatorSensorId);
        }
        sensorRepository.findBySimulatorSensorId(simulatorSensorId).ifPresentOrElse(
            existing -> {
                if (!existing.getEnabled()) {
                    existing.setEnabled(true);
                    if (displayName != null && !displayName.isBlank()) {
                        existing.setDisplayName(displayName);
                    }
                    sensorRepository.save(existing);
                    log.info("传感器已自动恢复启用: simulatorSensorId={}", simulatorSensorId);
                }
            },
            () -> {
                String name = (displayName != null && !displayName.isBlank())
                        ? displayName : sensorType + "_" + simulatorSensorId;
                Sensor sensor = Sensor.builder()
                        .sensorType(sensorType)
                        .displayName(name)
                        .dataTopic(correctTopic)
                        .reportFrequency(5)
                        .enabled(true)
                        .simulatorSensorId(simulatorSensorId)
                        .build();
                sensorRepository.save(sensor);
                log.info("传感器自动注册（被删除后重新识别）: simulatorSensorId={}, displayName={}, type={}",
                        simulatorSensorId, name, sensorType);
            }
        );
    }
}
