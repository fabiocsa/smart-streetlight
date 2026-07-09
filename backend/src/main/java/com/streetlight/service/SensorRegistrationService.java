package com.streetlight.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streetlight.entity.Sensor;
import com.streetlight.mqtt.MqttClientManager;
import com.streetlight.repository.DeviceRepository;
import com.streetlight.repository.SensorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 传感器注册服务（仅处理传感器 MQTT 自动发现）。
 * <p>
 * 设备（路灯）仅通过 REST API 管理，不经过 MQTT。
 * 传感器挂载在已有设备下，通过 MQTT 自动注册/注销。
 */
@Service
@Slf4j
public class SensorRegistrationService {

    private final DeviceRepository deviceRepository;
    private final SensorRepository sensorRepository;
    private final MqttClientManager mqttClientManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SensorRegistrationService(DeviceRepository deviceRepository,
                                      SensorRepository sensorRepository,
                                      @Lazy MqttClientManager mqttClientManager) {
        this.deviceRepository = deviceRepository;
        this.sensorRepository = sensorRepository;
        this.mqttClientManager = mqttClientManager;
    }

    /**
     * 处理传感器注册消息。
     * 传感器必须挂载到已存在的设备下，设备需预先通过 REST API 创建。
     */
    @Transactional
    public void handleSensorRegister(String deviceId, JsonNode payload) {
        // 验证设备存在
        if (deviceRepository.findByDeviceId(deviceId).isEmpty()) {
            log.warn("传感器注册失败: 设备 {} 不存在（请先通过 REST API 创建设备）", deviceId);
            return;
        }

        String sensorType = payload.has("sensorType")
                ? payload.get("sensorType").asText() : "light";
        String displayName = payload.has("displayName")
                ? payload.get("displayName").asText() : sensorType;
        String dataTopic = payload.has("dataTopic")
                ? payload.get("dataTopic").asText()
                : "streetlight/" + deviceId + "/sensor/data";
        int reportFrequency = payload.has("reportFrequency")
                ? payload.get("reportFrequency").asInt() : 5;
        String configJson = payload.has("configJson")
                ? payload.get("configJson").asText() : null;

        List<Sensor> existing = sensorRepository.findByDeviceIdAndSensorType(deviceId, sensorType);
        Sensor sensor;
        if (!existing.isEmpty()) {
            sensor = existing.get(0);
            sensor.setDisplayName(displayName);
            sensor.setDataTopic(dataTopic);
            sensor.setReportFrequency(reportFrequency);
            sensor.setEnabled(true);
            if (configJson != null) sensor.setConfigJson(configJson);
            sensor = sensorRepository.save(sensor);
            log.info("传感器已更新: id={}, deviceId={}, type={}", sensor.getId(), deviceId, sensorType);
        } else {
            sensor = Sensor.builder()
                    .deviceId(deviceId)
                    .sensorType(sensorType)
                    .displayName(displayName)
                    .dataTopic(dataTopic)
                    .reportFrequency(reportFrequency)
                    .enabled(true)
                    .configJson(configJson)
                    .build();
            sensor = sensorRepository.save(sensor);
            log.info("传感器已创建: id={}, deviceId={}, type={}", sensor.getId(), deviceId, sensorType);
        }

        // 确保已订阅该设备的数据主题
        mqttClientManager.subscribeDevice(deviceId);
    }

    /**
     * 处理传感器注销。
     */
    @Transactional
    public void handleSensorUnregister(String deviceId, Long sensorId) {
        sensorRepository.findById(sensorId).ifPresent(sensor -> {
            sensor.setEnabled(false);
            sensorRepository.save(sensor);
            log.info("传感器已注销: id={}, deviceId={}", sensorId, deviceId);
        });
    }
}
