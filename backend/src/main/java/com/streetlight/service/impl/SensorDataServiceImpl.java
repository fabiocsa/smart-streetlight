package com.streetlight.service.impl;

import com.streetlight.entity.Device;
import com.streetlight.entity.Sensor;
import com.streetlight.entity.SensorData;
import com.streetlight.mqtt.MqttPublishService;
import com.streetlight.repository.DeviceRepository;
import com.streetlight.repository.SensorDataRepository;
import com.streetlight.repository.SensorRepository;
import com.streetlight.service.AlarmService;
import com.streetlight.service.SensorDataService;
import com.streetlight.websocket.WebSocketHandler;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class SensorDataServiceImpl implements SensorDataService {

    private final SensorDataRepository sensorDataRepository;
    private final DeviceRepository deviceRepository;
    private final SensorRepository sensorRepository;
    private final AlarmService alarmService;
    private final MqttPublishService mqttPublishService;
    private final EntityManager entityManager;
    private final WebSocketHandler webSocketHandler;

    public SensorDataServiceImpl(SensorDataRepository sensorDataRepository,
                                  DeviceRepository deviceRepository,
                                  SensorRepository sensorRepository,
                                  AlarmService alarmService,
                                  MqttPublishService mqttPublishService,
                                  EntityManager entityManager,
                                  WebSocketHandler webSocketHandler) {
        this.sensorDataRepository = sensorDataRepository;
        this.deviceRepository = deviceRepository;
        this.sensorRepository = sensorRepository;
        this.alarmService = alarmService;
        this.mqttPublishService = mqttPublishService;
        this.entityManager = entityManager;
        this.webSocketHandler = webSocketHandler;
    }

    @Override
    @Transactional
    public SensorData saveAndAutoControl(String deviceId, Long sensorId, String sensorType,
                                          Map<String, Object> data, LocalDateTime reportedAt) {
        // 保存完整传感器数据（含未绑定传感器的数据，deviceId 可能为 sensor_{id}）
        SensorData sd = SensorData.from(deviceId, sensorId, sensorType, data, reportedAt);
        sensorDataRepository.save(sd);

        // 设备不存在或传感器未绑定时：自动注册/恢复传感器（容错恢复）
        if (deviceId.startsWith("sensor_")) {
            // sensorId 来自 MQTT topic，是模拟器内部 ID
            sensorRepository.findBySimulatorSensorId(sensorId).ifPresentOrElse(
                existing -> {
                    // 传感器存在但被禁用 → 自动恢复启用
                    if (!existing.getEnabled()) {
                        existing.setEnabled(true);
                        // 从 data payload 恢复 displayName（可能比 DB 中的更新）
                        if (data.containsKey("displayName") && data.get("displayName") != null) {
                            existing.setDisplayName(data.get("displayName").toString());
                        }
                        sensorRepository.save(existing);
                        log.info("传感器已自动恢复启用: simulatorSensorId={}", sensorId);
                    }
                },
                () -> {
                    // 传感器完全不存在（被硬删除或注册消息丢失）→ 自动注册
                    String displayName = null;
                    if (data.containsKey("displayName") && data.get("displayName") != null) {
                        displayName = data.get("displayName").toString();
                    }
                    if (displayName == null || displayName.isBlank()) {
                        displayName = sensorType + "_" + sensorId;
                    }
                    Sensor newSensor = Sensor.builder()
                            .sensorType(sensorType)
                            .displayName(displayName)
                            .dataTopic("streetlight/sensor/" + sensorId + "/data")
                            .reportFrequency(5)
                            .enabled(true)
                            .simulatorSensorId(sensorId)
                            .build();
                    sensorRepository.save(newSensor);
                    log.info("传感器自动注册（被删除后重新识别）: simulatorSensorId={}, displayName={}, type={}",
                            sensorId, displayName, sensorType);
                }
            );
            log.debug("传感器未绑定设备，跳过联动: deviceId={}, sensorId={}", deviceId, sensorId);
            return sd;
        }

        Optional<Device> deviceOpt = deviceRepository.findByDeviceId(deviceId);
        if (deviceOpt.isEmpty()) {
            log.debug("设备 {} 不存在（可能已被删除），跳过联动", deviceId);
            return sd;
        }
        Device device = deviceOpt.get();

        // 从 data map 中提取光照强度用于自动联动
        Double lightIntensity = extractLightIntensity(data);

        boolean wasOffline = "offline".equals(device.getStatus());
        device.setLastHeartbeat(LocalDateTime.now());
        if (wasOffline) {
            device.setStatus("online");
            alarmService.autoResolveOfflineAlarm(deviceId);
            log.info("设备恢复在线: deviceId={}", deviceId);
        }
        deviceRepository.save(device);

        // 推送传感器数据到 WebSocket
        webSocketHandler.pushSensorData(deviceId, sensorType, data,
                reportedAt != null ? reportedAt.toString() : LocalDateTime.now().toString());
        log.debug("已推送传感器数据到WebSocket: deviceId={}, sensorType={}, fields={}",
                deviceId, sensorType, data.keySet());

        // 光照联动控制（仅 light 类型传感器触发）
        if (lightIntensity != null && "light".equals(sensorType)) {
            entityManager.flush();
            entityManager.clear();
            Device freshDevice = deviceRepository.findByDeviceId(deviceId).orElse(device);

            if ("auto".equals(freshDevice.getControlMode())) {
                String cmd = null;
                if ("off".equals(freshDevice.getLightStatus()) && lightIntensity < freshDevice.getThresholdOn()) {
                    cmd = "on";
                } else if ("on".equals(freshDevice.getLightStatus()) && lightIntensity > freshDevice.getThresholdOff()) {
                    cmd = "off";
                }
                if (cmd != null) {
                    try {
                        mqttPublishService.publishCommand(deviceId, cmd, "auto");
                        log.info("自动联动: deviceId={}, light={}, cmd={}", deviceId, lightIntensity, cmd);
                    } catch (Exception e) {
                        log.warn("自动联动MQTT发送失败: deviceId={}, cmd={}, {}", deviceId, cmd, e.getMessage());
                    }
                }
            }
        }

        return sd;
    }

    /**
     * 从 data map 中提取光照强度值。
     * 优先取 illuminance，其次 lightIntensity。
     */
    private Double extractLightIntensity(Map<String, Object> data) {
        if (data == null) return null;
        Object illuminance = data.get("illuminance");
        if (illuminance instanceof Number) {
            return ((Number) illuminance).doubleValue();
        }
        Object light = data.get("lightIntensity");
        if (light instanceof Number) {
            return ((Number) light).doubleValue();
        }
        return null;
    }

    @Override
    public Optional<SensorData> getLatestByDeviceId(String deviceId) {
        return sensorDataRepository.findTopByDeviceIdOrderByReportedAtDesc(deviceId);
    }

    @Override
    public Optional<SensorData> getLatestByDeviceIdAndSensorType(String deviceId, String sensorType) {
        return sensorDataRepository.findTopByDeviceIdAndSensorTypeOrderByReportedAtDesc(deviceId, sensorType);
    }

    @Override
    public List<SensorData> getHistory(String deviceId, LocalDateTime start, LocalDateTime end) {
        return sensorDataRepository.findByDeviceIdAndReportedAtBetweenOrderByReportedAtAsc(deviceId, start, end);
    }

    @Override
    public List<SensorData> getHistoryBySensorType(String deviceId, String sensorType,
                                                    LocalDateTime start, LocalDateTime end) {
        return sensorDataRepository.findBySensorTypeAndReportedAtBetweenOrderByReportedAtAsc(sensorType, start, end);
    }

    @Override
    public Map<String, Object> getStats(String deviceId, String field,
                                         LocalDateTime start, LocalDateTime end) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("field", field);
        stats.put("avg", sensorDataRepository.avgByField(deviceId, field, start, end));
        stats.put("max", sensorDataRepository.maxByField(deviceId, field, start, end));
        stats.put("min", sensorDataRepository.minByField(deviceId, field, start, end));
        stats.put("count", sensorDataRepository.countByDeviceIdAndTimeRange(deviceId, start, end));
        return stats;
    }
}
