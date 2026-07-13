package com.streetlight.service.impl;

import com.streetlight.entity.ControlLog;
import com.streetlight.entity.Device;
import com.streetlight.entity.Sensor;
import com.streetlight.entity.SensorData;
import com.streetlight.mqtt.MqttPublishService;
import com.streetlight.repository.ControlLogRepository;
import com.streetlight.repository.DeviceRepository;
import com.streetlight.repository.SensorDataRepository;
import com.streetlight.repository.SensorRepository;
import com.streetlight.repository.SystemConfigRepository;
import com.streetlight.service.AlarmService;
import com.streetlight.service.SensorDataService;
import com.streetlight.service.SensorRegistrationService;
import com.streetlight.websocket.WebSocketHandler;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final ControlLogRepository controlLogRepository;
    private final AlarmService alarmService;
    private final MqttPublishService mqttPublishService;
    private final EntityManager entityManager;
    private final WebSocketHandler webSocketHandler;
    private final SensorRegistrationService sensorRegistrationService;
    private final SystemConfigRepository systemConfigRepository;

    public SensorDataServiceImpl(SensorDataRepository sensorDataRepository,
                                  DeviceRepository deviceRepository,
                                  SensorRepository sensorRepository,
                                  ControlLogRepository controlLogRepository,
                                  AlarmService alarmService,
                                  MqttPublishService mqttPublishService,
                                  EntityManager entityManager,
                                  WebSocketHandler webSocketHandler,
                                  SensorRegistrationService sensorRegistrationService,
                                  SystemConfigRepository systemConfigRepository) {
        this.sensorDataRepository = sensorDataRepository;
        this.deviceRepository = deviceRepository;
        this.sensorRepository = sensorRepository;
        this.controlLogRepository = controlLogRepository;
        this.alarmService = alarmService;
        this.mqttPublishService = mqttPublishService;
        this.entityManager = entityManager;
        this.webSocketHandler = webSocketHandler;
        this.sensorRegistrationService = sensorRegistrationService;
        this.systemConfigRepository = systemConfigRepository;
    }

    @Override
    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public SensorData saveAndAutoControl(String deviceId, Long sensorId, String sensorType,
                                          Map<String, Object> data, LocalDateTime reportedAt) {
        // ===== 第1步：保存数据（UK 充当分布式锁，决定哪个实例走自动联动） =====
        SensorData sd = SensorData.from(deviceId, sensorId, sensorType, data, reportedAt);
        boolean iAmFirst = true;  // 本实例是否是第一个入库的
        try {
            sensorDataRepository.save(sd);
        } catch (DataIntegrityViolationException e) {
            iAmFirst = false;
            entityManager.clear();
            log.debug("重复消息（多实例去重）: sensorId={}, reportedAt={}", sensorId, reportedAt);
            // 不回滚、不 return！心跳和 WebSocket 仍需本实例执行（为连接到此实例的前端服务）
        }

        if (iAmFirst) {
            log.info("[自动联动诊断] deviceId={}, sensorId={}, sensorType={}, 是否未绑定={}, data字段={}",
                    deviceId, sensorId, sensorType, deviceId.startsWith("sensor_"), data.keySet());
        }

        // ===== 告警检测（在设备绑定检查之前执行，确保未绑定设备也能触发告警） =====
        log.info("[告警检测] iAmFirst={}, dataKeys={}, hasVoltage={}, hasTemp={}, hasPower={}",
                iAmFirst, data.keySet(),
                data.containsKey("voltage") && data.get("voltage") instanceof Number,
                data.containsKey("temperature") && data.get("temperature") instanceof Number,
                data.containsKey("power") && data.get("power") instanceof Number);

        // 电压异常检测（仅 light / power 传感器携带电压数据）
        if (iAmFirst && ("light".equals(sensorType) || "power".equals(sensorType))
                && data.containsKey("voltage") && data.get("voltage") instanceof Number) {
            Double voltage = ((Number) data.get("voltage")).doubleValue();
            Double voltageMin = getConfigDouble("voltage_min", 210.0);
            Double voltageMax = getConfigDouble("voltage_max", 240.0);
            log.info("[告警检测] 电压检测: voltage={}, min={}, max={}, 是否异常={}",
                    voltage, voltageMin, voltageMax, voltage > voltageMax || voltage < voltageMin);

            if (voltage > voltageMax || voltage < voltageMin) {
                log.warn("[告警检测] 触发电压异常告警! deviceId={}, voltage={}", deviceId, voltage);
                alarmService.createVoltageAbnormalAlarm(deviceId, voltage, voltageMin, voltageMax);
            } else {
                alarmService.autoResolveVoltageAlarm(deviceId);
            }
        } else {
            log.info("[告警检测] 跳过电压检测: iAmFirst={}, hasKey={}, isNumber={}",
                    iAmFirst, data.containsKey("voltage"),
                    data.containsKey("voltage") ? data.get("voltage") instanceof Number : "N/A");
        }

        // 温度过高检测（仅 temperature 传感器携带温度数据）
        if (iAmFirst && "temperature".equals(sensorType)
                && data.containsKey("temperature") && data.get("temperature") instanceof Number) {
            Double temperature = ((Number) data.get("temperature")).doubleValue();
            Double tempMax = getConfigDouble("temperature_max", 45.0);
            log.info("[告警检测] 温度检测: temperature={}, max={}, 是否异常={}",
                    temperature, tempMax, temperature > tempMax);

            if (temperature > tempMax) {
                log.warn("[告警检测] 触发温度过高告警! deviceId={}, temperature={}", deviceId, temperature);
                alarmService.createTemperatureHighAlarm(deviceId, temperature, tempMax);
            } else {
                alarmService.autoResolveTemperatureAlarm(deviceId);
            }
        } else {
            log.info("[告警检测] 跳过温度检测: iAmFirst={}, hasKey={}, isNumber={}",
                    iAmFirst, data.containsKey("temperature"),
                    data.containsKey("temperature") ? data.get("temperature") instanceof Number : "N/A");
        }

        // 功率过高检测（仅 light / power 传感器携带功率数据）
        if (iAmFirst && ("light".equals(sensorType) || "power".equals(sensorType))
                && data.containsKey("power") && data.get("power") instanceof Number) {
            Double power = ((Number) data.get("power")).doubleValue();
            Double powerMax = getConfigDouble("power_max", 100.0);
            log.info("[告警检测] 功率检测: power={}, max={}, 是否异常={}",
                    power, powerMax, power > powerMax);

            if (power > powerMax) {
                log.warn("[告警检测] 触发功率过高告警! deviceId={}, power={}", deviceId, power);
                alarmService.createPowerHighAlarm(deviceId, power, powerMax);
            } else {
                alarmService.autoResolvePowerAlarm(deviceId);
            }
        } else {
            log.info("[告警检测] 跳过功率检测: iAmFirst={}, hasKey={}, isNumber={}",
                    iAmFirst, data.containsKey("power"),
                    data.containsKey("power") ? data.get("power") instanceof Number : "N/A");
        }

        // ===== 第2步：传感器未绑定时，自动注册 =====
        // ★ autoRegisterFromData 内部已有 findBySimulatorSensorId 去重，
        //    因此不需要 iAmFirst 保护。确保即使数据入库发生 UK 冲突也能注册。
        if (deviceId.startsWith("sensor_")) {
            try {
                String displayName = data.containsKey("displayName") && data.get("displayName") != null
                        ? data.get("displayName").toString() : null;
                sensorRegistrationService.autoRegisterFromData(
                        sensorId, sensorType, displayName,
                        "streetlight/sensor/" + sensorId + "/data");
            } catch (Exception e) {
                log.warn("传感器自动注册失败: sensorId={}, error={}", sensorId, e.getMessage());
            }
            log.info("传感器未绑定设备，跳过自动联动。");
            return sd;
        }

        // ===== 第3步：查找设备 + 更新心跳（所有实例都做，收益 > 成本） =====
        Optional<Device> deviceOpt = deviceRepository.findByDeviceId(deviceId);
        if (deviceOpt.isEmpty()) {
            log.info("设备 {} 不存在，跳过", deviceId);
            return sd;
        }
        Device device = deviceOpt.get();
        Double lightIntensity = extractLightIntensity(data);

        boolean wasOffline = "offline".equals(device.getStatus());
        device.setLastHeartbeat(LocalDateTime.now());
        if (wasOffline) {
            device.setStatus("online");
            if (iAmFirst) {
                alarmService.autoResolveOfflineAlarm(deviceId);
                log.info("设备恢复在线: deviceId={}", deviceId);
            }
        }
        deviceRepository.save(device);

        // ★ 推送设备状态变更（所有实例都做，确保每个前端都能收到）
        if (wasOffline) {
            webSocketHandler.pushDeviceStatus(deviceId, "online", device.getLightStatus());
        }

        // ===== 第4步：WebSocket 推送（所有实例都做，确保每个前端都能收到实时数据） =====
        webSocketHandler.pushSensorData(deviceId, sensorType, data,
                reportedAt != null ? reportedAt.toString() : LocalDateTime.now().toString());

        // ===== 以下自动联动逻辑仅第一个入库的实例执行（避免重复控制指令） =====
        if (!iAmFirst) {
            return sd;  // 其他实例到此为止：心跳已更新、WebSocket 已推送，联动交给赢家
        }

        // ===== 第6步：判断是否需要进入自动联动决策 =====
        if (lightIntensity == null) {
            log.info("传感器数据中无光照字段(illuminance/lightIntensity)，跳过自动联动。" +
                     " deviceId={}, sensorType={}, fields={}", deviceId, sensorType, data.keySet());
            return sd;
        }
        if (!"light".equals(sensorType)) {
            log.info("传感器类型非 light(sensorType={})，跳过自动联动。 deviceId={}", sensorType, deviceId);
            return sd;
        }

        // ===== 第7步：悲观锁读取设备最新状态，防止并发重复触发 =====
        // flush + clear 将心跳更新刷入 DB 并清空一级缓存，
        // 确保后续 findByDeviceIdForUpdate 命中 DB 行锁而非 JPA 缓存。
        entityManager.flush();
        entityManager.clear();

        Device lockedDevice = deviceRepository.findByDeviceIdForUpdate(deviceId).orElse(null);
        if (lockedDevice == null) {
            log.warn("设备 {} 在锁定读取时不存在，跳过自动联动", deviceId);
            return sd;
        }

        // ===== 第8步：检查控制模式（持有行锁） =====
        if (!"auto".equals(lockedDevice.getControlMode())) {
            log.info("设备 {} 控制模式为 {}（持锁读取），跳过自动联动。光照={}",
                    deviceId, lockedDevice.getControlMode(), lightIntensity);
            return sd;
        }

        // ===== 第9步：多传感器策略 — 重新计算决策光照值 =====
        String strategy = lockedDevice.getSensorStrategy();
        Long primarySensorId = lockedDevice.getPrimarySensorId();

        // 策略A：指定主传感器 — 非主传感器上报的数据跳过
        if ("single".equals(strategy) && primarySensorId != null && sensorId != null) {
            if (!primarySensorId.equals(sensorId)) {
                log.info("设备 {} 主传感器为 {}，忽略传感器 {} 的光照数据（策略=single）",
                        deviceId, primarySensorId, sensorId);
                return sd;
            }
            log.info("设备 {} 使用主传感器 {} 的光照值进行决策: {}",
                    deviceId, primarySensorId, lightIntensity);
        }

        // 策略B：平均值 — 取所有已绑定 light 传感器最新数据的平均值
        if ("average".equals(strategy)) {
            List<Sensor> boundLightSensors = sensorRepository
                    .findBoundSensorsByDeviceIdAndType(deviceId, "light");
            if (boundLightSensors.size() > 1) {
                double sum = 0;
                int count = 0;
                for (Sensor bs : boundLightSensors) {
                    SensorData latest = sensorDataRepository
                            .findTopBySensorIdOrderByReportedAtDesc(bs.getId())
                            .orElse(null);
                    if (latest != null) {
                        Double val = latest.getIlluminance();
                        if (val == null) val = latest.getLightIntensity();
                        if (val != null) {
                            sum += val;
                            count++;
                        }
                    }
                }
                if (count > 0) {
                    double avg = sum / count;
                    log.info("设备 {} 使用 {} 个 light 传感器平均值决策: avg={}, 各传感器数={}",
                            deviceId, count, avg, count);
                    lightIntensity = avg;
                } else {
                    log.info("设备 {} 策略=average 但无可用光照数据，使用当前上报值: {}",
                            deviceId, lightIntensity);
                }
            } else {
                log.info("设备 {} 策略=average 但仅有 {} 个 light 传感器，使用当前上报值",
                        deviceId, boundLightSensors.size());
            }
        }

        // ===== 第10步：迟滞双阈值联动决策（持有行锁） =====
        String cmd = null;
        if ("off".equals(lockedDevice.getLightStatus()) && lightIntensity < lockedDevice.getThresholdOn()) {
            cmd = "on";
            log.info("触发自动开灯: deviceId={}, 光照={} < 开灯阈值={}",
                    deviceId, lightIntensity, lockedDevice.getThresholdOn());
        } else if ("on".equals(lockedDevice.getLightStatus()) && lightIntensity > lockedDevice.getThresholdOff()) {
            cmd = "off";
            log.info("触发自动关灯: deviceId={}, 光照={} > 关灯阈值={}",
                    deviceId, lightIntensity, lockedDevice.getThresholdOff());
        } else {
            log.info("光照在阈值区间内，不触发联动: deviceId={}, 光照={}, " +
                     "开灯阈值={}, 关灯阈值={}, 当前灯状态={}",
                     deviceId, lightIntensity, lockedDevice.getThresholdOn(),
                     lockedDevice.getThresholdOff(), lockedDevice.getLightStatus());
        }

        if (cmd != null) {
            // 查找设备绑定的 light 传感器，通过传感器 cmd 主题下发指令
            List<Sensor> lightSensors = sensorRepository
                    .findBoundSensorsByDeviceIdAndType(deviceId, "light");
            if (lightSensors.isEmpty()) {
                log.warn("设备 {} 未绑定 light 传感器，无法下发控制指令", deviceId);
            } else {
                // ★ 先写控制日志（result=null），等传感器 cmd response 回填
                ControlLog clog = ControlLog.builder()
                        .deviceId(deviceId).command(cmd).source("auto").build();
                controlLogRepository.save(clog);

                boolean allSent = true;
                for (Sensor s : lightSensors) {
                    Long targetSensorId = s.getSimulatorSensorId() != null
                            ? s.getSimulatorSensorId() : s.getId();
                    try {
                        mqttPublishService.publishCommand(targetSensorId, deviceId, cmd, "auto");
                    } catch (Exception e) {
                        allSent = false;
                        log.warn("自动联动MQTT发送失败: sensorId={}, deviceId={}, cmd={}, {}",
                                targetSensorId, deviceId, cmd, e.getMessage());
                    }
                }
                if (allSent) {
                    lockedDevice.setLightStatus(cmd);
                    deviceRepository.save(lockedDevice);
                    // ★ 推送设备状态变更，前端实时更新灯光状态
                    webSocketHandler.pushDeviceStatus(deviceId, lockedDevice.getStatus(), cmd);
                    log.info("自动联动指令已发送并更新DB: deviceId={}, cmd={}, sensors={}, controlLogId={}",
                            deviceId, cmd, lightSensors.size(), clog.getId());
                }
                // 部分失败时不更新 lightStatus，下次数据到来会重试
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
        return sensorDataRepository.findByDeviceIdAndSensorTypeAndReportedAtBetweenOrderByReportedAtAsc(
                deviceId, sensorType, start, end);
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

    // ==================== 电压配置辅助方法 ====================

    private Double getConfigDouble(String key, Double defaultValue) {
        return systemConfigRepository.findByConfigKey(key)
                .map(c -> {
                    try { return Double.parseDouble(c.getConfigValue()); }
                    catch (NumberFormatException e) { return defaultValue; }
                })
                .orElse(defaultValue);
    }
}
