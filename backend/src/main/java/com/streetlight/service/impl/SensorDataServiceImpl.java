package com.streetlight.service.impl;

import com.streetlight.entity.Device;
import com.streetlight.entity.SensorData;
import com.streetlight.mqtt.MqttClientManager;
import com.streetlight.mqtt.MqttPublishService;
import com.streetlight.repository.DeviceRepository;
import com.streetlight.repository.SensorDataRepository;
import com.streetlight.service.AlarmService;
import com.streetlight.service.SensorDataService;
import com.streetlight.websocket.WebSocketHandler;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
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
    private final ApplicationContext applicationContext;
    private final AlarmService alarmService;
    private final MqttPublishService mqttPublishService;
    private final EntityManager entityManager;
    private final WebSocketHandler webSocketHandler;

    public SensorDataServiceImpl(SensorDataRepository sensorDataRepository,
                                  DeviceRepository deviceRepository,
                                  ApplicationContext applicationContext,
                                  AlarmService alarmService,
                                  MqttPublishService mqttPublishService,
                                  EntityManager entityManager,
                                  WebSocketHandler webSocketHandler) {
        this.sensorDataRepository = sensorDataRepository;
        this.deviceRepository = deviceRepository;
        this.applicationContext = applicationContext;
        this.alarmService = alarmService;
        this.mqttPublishService = mqttPublishService;
        this.entityManager = entityManager;
        this.webSocketHandler = webSocketHandler;
    }

    private MqttClientManager getMqttClientManager() {
        return applicationContext.getBean(MqttClientManager.class);
    }

    @Override
    @Transactional
    public SensorData saveAndAutoControl(String deviceId, Long sensorId, String sensorType,
                                          Map<String, Object> data, LocalDateTime reportedAt) {
        // 保存完整传感器数据
        SensorData sd = SensorData.from(deviceId, sensorId, sensorType, data, reportedAt);
        sensorDataRepository.save(sd);

        // 从 data map 中提取光照强度用于自动联动
        Double lightIntensity = extractLightIntensity(data);

        // 自动注册设备
        Device device = deviceRepository.findByDeviceId(deviceId).orElseGet(() -> {
            Device newDevice = Device.builder()
                    .deviceId(deviceId)
                    .name(deviceId)
                    .status("online")
                    .lightStatus("off")
                    .controlMode("auto")
                    .thresholdOn(50.0)
                    .thresholdOff(100.0)
                    .location("")
                    .build();
            Device saved = deviceRepository.save(newDevice);
            getMqttClientManager().subscribeDevice(deviceId);
            log.info("MQTT消息自动注册设备 - deviceId: {}", deviceId);
            return saved;
        });

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
