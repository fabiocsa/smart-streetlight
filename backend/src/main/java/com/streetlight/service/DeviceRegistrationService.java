package com.streetlight.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streetlight.entity.Device;
import com.streetlight.entity.Sensor;
import com.streetlight.mqtt.MqttClientManager;
import com.streetlight.mqtt.MqttPublishService;
import com.streetlight.repository.DeviceRepository;
import com.streetlight.repository.SensorRepository;
import com.streetlight.websocket.WebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 设备注册与自动发现服务。
 * <p>
 * 模拟器/真实设备通过 MQTT 发送注册消息（topic: streetlight/register），
 * 后端自动解析设备及传感器元数据，在数据库中创建或更新记录，
 * 并主动订阅该设备的数据主题，完成自动发现。
 */
@Service
@Slf4j
public class DeviceRegistrationService {

    private final DeviceRepository deviceRepository;
    private final SensorRepository sensorRepository;
    private final MqttClientManager mqttClientManager;
    private final MqttPublishService mqttPublishService;
    private final WebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DeviceRegistrationService(DeviceRepository deviceRepository,
                                      SensorRepository sensorRepository,
                                      MqttClientManager mqttClientManager,
                                      MqttPublishService mqttPublishService,
                                      WebSocketHandler webSocketHandler) {
        this.deviceRepository = deviceRepository;
        this.sensorRepository = sensorRepository;
        this.mqttClientManager = mqttClientManager;
        this.mqttPublishService = mqttPublishService;
        this.webSocketHandler = webSocketHandler;
    }

    /**
     * 处理设备注册消息。
     * 解析 payload → upsert Device → upsert Sensors → 订阅主题 → 返回确认。
     */
    @Transactional
    public void handleDeviceRegistration(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String deviceId = root.get("deviceId").asText();
            String name = root.has("name") ? root.get("name").asText() : deviceId;
            String location = root.has("location") ? root.get("location").asText() : "";

            log.info("处理设备注册: deviceId={}, name={}, location={}", deviceId, name, location);

            // 1. 创建或更新设备
            Device device = deviceRepository.findByDeviceId(deviceId).orElseGet(() -> {
                Device d = Device.builder()
                        .deviceId(deviceId)
                        .name(name)
                        .location(location)
                        .status("online")
                        .lightStatus("off")
                        .controlMode("auto")
                        .thresholdOn(50.0)
                        .thresholdOff(100.0)
                        .build();
                return deviceRepository.save(d);
            });

            // 更新设备信息
            if (name != null && !name.equals(deviceId)) {
                device.setName(name);
            }
            if (location != null && !location.isBlank()) {
                device.setLocation(location);
            }
            device.setStatus("online");
            device.setLastHeartbeat(LocalDateTime.now());
            device = deviceRepository.save(device);

            // 2. 处理传感器列表
            List<Map<String, Object>> sensorResults = new ArrayList<>();
            JsonNode sensorsNode = root.get("sensors");
            if (sensorsNode != null && sensorsNode.isArray()) {
                for (JsonNode sensorNode : sensorsNode) {
                    Map<String, Object> result = upsertSensor(deviceId, sensorNode);
                    sensorResults.add(result);
                }
            }

            // 3. 订阅该设备的数据主题
            mqttClientManager.subscribeDevice(deviceId);

            // 4. 推送设备上线状态
            webSocketHandler.pushDeviceStatus(deviceId, "online", device.getLightStatus());

            // 5. 发布注册确认
            mqttPublishService.publishRegistrationAck(deviceId, "registered", sensorResults);

            log.info("设备注册完成: deviceId={}, sensors={}", deviceId, sensorResults.size());

        } catch (JsonProcessingException e) {
            log.error("解析设备注册消息失败: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("处理设备注册异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理设备注销消息。
     */
    @Transactional
    public void handleDeviceDeregistration(String deviceId) {
        log.info("处理设备注销: deviceId={}", deviceId);
        deviceRepository.findByDeviceId(deviceId).ifPresent(device -> {
            device.setStatus("offline");
            deviceRepository.save(device);
            webSocketHandler.pushDeviceStatus(deviceId, "offline", device.getLightStatus());
        });
    }

    /**
     * 处理单个传感器动态注册。
     */
    @Transactional
    public void handleSensorRegister(String deviceId, JsonNode payload) {
        log.info("处理传感器动态注册: deviceId={}", deviceId);
        upsertSensor(deviceId, payload);
    }

    /**
     * 处理传感器动态注销。
     */
    @Transactional
    public void handleSensorUnregister(String deviceId, Long sensorId) {
        log.info("处理传感器注销: deviceId={}, sensorId={}", deviceId, sensorId);
        sensorRepository.findById(sensorId).ifPresent(sensor -> {
            sensor.setEnabled(false);
            sensorRepository.save(sensor);
        });
    }

    // ============ 内部方法 ============

    /**
     * 创建或更新单个传感器记录。
     */
    private Map<String, Object> upsertSensor(String deviceId, JsonNode sensorNode) {
        Map<String, Object> result = new LinkedHashMap<>();

        String sensorType = sensorNode.has("sensorType")
                ? sensorNode.get("sensorType").asText() : "light";
        String displayName = sensorNode.has("displayName")
                ? sensorNode.get("displayName").asText() : sensorType;
        String dataTopic = sensorNode.has("dataTopic")
                ? sensorNode.get("dataTopic").asText()
                : "streetlight/" + deviceId + "/sensor/data";
        int reportFrequency = sensorNode.has("reportFrequency")
                ? sensorNode.get("reportFrequency").asInt() : 5;
        String configJson = sensorNode.has("configJson")
                ? sensorNode.get("configJson").asText() : null;

        result.put("sensorType", sensorType);
        result.put("displayName", displayName);

        // 查找是否已存在同设备+同类型的传感器
        List<Sensor> existing = sensorRepository.findByDeviceIdAndSensorType(deviceId, sensorType);
        Sensor sensor;
        if (!existing.isEmpty()) {
            // 更新已有传感器
            sensor = existing.get(0);
            sensor.setDisplayName(displayName);
            sensor.setDataTopic(dataTopic);
            sensor.setReportFrequency(reportFrequency);
            sensor.setEnabled(true);
            if (configJson != null) {
                sensor.setConfigJson(configJson);
            }
            sensor = sensorRepository.save(sensor);
            result.put("status", "updated");
            result.put("sensorId", sensor.getId());
            log.info("传感器已更新: id={}, deviceId={}, type={}", sensor.getId(), deviceId, sensorType);
        } else {
            // 新建
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
            result.put("status", "created");
            result.put("sensorId", sensor.getId());
            log.info("传感器已创建: id={}, deviceId={}, type={}", sensor.getId(), deviceId, sensorType);
        }

        return result;
    }
}
