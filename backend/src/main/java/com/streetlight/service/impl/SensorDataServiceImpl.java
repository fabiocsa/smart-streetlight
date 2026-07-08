package com.streetlight.service.impl;

import com.streetlight.entity.Device;
import com.streetlight.entity.SensorData;
import com.streetlight.mqtt.MqttClientManager;
import com.streetlight.mqtt.MqttPublishService;
import com.streetlight.repository.DeviceRepository;
import com.streetlight.repository.SensorDataRepository;
import com.streetlight.service.AlarmService;
import com.streetlight.service.SensorDataService;
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

    public SensorDataServiceImpl(SensorDataRepository sensorDataRepository,
                                  DeviceRepository deviceRepository,
                                  ApplicationContext applicationContext,
                                  AlarmService alarmService,
                                  MqttPublishService mqttPublishService) {
        this.sensorDataRepository = sensorDataRepository;
        this.deviceRepository = deviceRepository;
        this.applicationContext = applicationContext;
        this.alarmService = alarmService;
        this.mqttPublishService = mqttPublishService;
    }

    /**
     * 延迟获取 MqttClientManager，避免循环依赖：
     * MqttClientManager → MqttMessageHandler → SensorDataServiceImpl → MqttClientManager
     */
    private MqttClientManager getMqttClientManager() {
        return applicationContext.getBean(MqttClientManager.class);
    }

    @Override
    @Transactional
    public SensorData saveAndAutoControl(String deviceId, Double lightIntensity, LocalDateTime reportedAt) {
        SensorData data = SensorData.builder()
                .deviceId(deviceId).lightIntensity(lightIntensity).reportedAt(reportedAt).build();
        sensorDataRepository.save(data);

        // 自动注册设备（如果不存在则创建）
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
            // 订阅该设备的MQTT主题
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

        if ("auto".equals(device.getControlMode())) {
            String cmd = null;
            if ("off".equals(device.getLightStatus()) && lightIntensity < device.getThresholdOn()) {
                cmd = "on";
            } else if ("on".equals(device.getLightStatus()) && lightIntensity > device.getThresholdOff()) {
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
        return data;
    }

    @Override
    public Optional<SensorData> getLatestByDeviceId(String deviceId) {
        return sensorDataRepository.findTopByDeviceIdOrderByReportedAtDesc(deviceId);
    }

    @Override
    public List<SensorData> getHistory(String deviceId, LocalDateTime start, LocalDateTime end) {
        return sensorDataRepository.findByDeviceIdAndReportedAtBetweenOrderByReportedAtAsc(deviceId, start, end);
    }

    @Override
    public Map<String, Object> getStats(String deviceId, LocalDateTime start, LocalDateTime end) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("avg", sensorDataRepository.avgLightIntensity(deviceId, start, end));
        stats.put("max", sensorDataRepository.maxLightIntensity(deviceId, start, end));
        stats.put("min", sensorDataRepository.minLightIntensity(deviceId, start, end));
        stats.put("count", sensorDataRepository.countByDeviceIdAndTimeRange(deviceId, start, end));
        return stats;
    }
}
