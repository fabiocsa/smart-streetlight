package com.streetlight.service.impl;

import com.streetlight.common.BusinessException;
import com.streetlight.entity.ControlLog;
import com.streetlight.entity.Device;
import com.streetlight.entity.Sensor;
import com.streetlight.mqtt.MqttPublishService;
import com.streetlight.repository.ControlLogRepository;
import com.streetlight.repository.DeviceRepository;
import com.streetlight.repository.SensorRepository;
import com.streetlight.service.ControlService;
import com.streetlight.websocket.WebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ControlServiceImpl implements ControlService {

    private final ControlLogRepository controlLogRepository;
    private final DeviceRepository deviceRepository;
    private final SensorRepository sensorRepository;
    private final MqttPublishService mqttPublishService;
    private final WebSocketHandler webSocketHandler;

    @Override
    @Transactional
    public void sendControlCommand(String deviceId, String command, String source) {
        sendControlCommand(deviceId, command, source, null);
    }

    @Override
    @Transactional
    public void sendControlCommand(String deviceId, String command, String source, Integer brightness) {
        // 校验亮度范围
        if (brightness != null && (brightness < 0 || brightness > 100)) {
            throw new BusinessException("亮度必须在 0-100 之间");
        }
        // 记录控制日志
        ControlLog cl = ControlLog.builder()
                .deviceId(deviceId).command(command).source(source).build();
        controlLogRepository.save(cl);

        // 更新设备状态（在 MQTT 下发前，避免并发自动联动读到旧 lightStatus 重复触发）
        deviceRepository.findByDeviceId(deviceId).ifPresent(d -> {
            d.setLightStatus(command);
            if (brightness != null) d.setBrightness(brightness);
            if ("manual".equals(source)) {
                d.setControlMode("manual");
            }
            deviceRepository.save(d);
            // ★ 推送设备状态变更（含控制模式），前端实时更新
            webSocketHandler.pushDeviceStatus(deviceId, d.getStatus(), command, d.getControlMode());
            log.info("控制指令已更新设备状态: deviceId={}, command={}, source={}, lightStatus={}",
                    deviceId, command, source, command);
        });

        // v3: 查找设备绑定的 light 传感器，通过传感器 cmd 主题下发指令
        List<Sensor> lightSensors = sensorRepository
                .findBoundSensorsByDeviceIdAndType(deviceId, "light");
        if (lightSensors.isEmpty()) {
            log.warn("设备 {} 未绑定 light 传感器，无法下发控制指令（仅更新了 DB）", deviceId);
        } else {
            for (Sensor s : lightSensors) {
                Long targetSensorId = s.getSimulatorSensorId() != null
                        ? s.getSimulatorSensorId() : s.getId();
                mqttPublishService.publishCommand(targetSensorId, deviceId, command, source, brightness);
            }
        }

        log.info("下发控制指令: deviceId={}, command={}, source={}, brightness={}, sensors={}",
                deviceId, command, source, brightness, lightSensors.size());
    }

    @Override
    @Transactional
    public void updateControlResult(String deviceId, String command, String result) {
        // 取该设备+指令中最新一条未收到响应的控制日志（避免多条 pending 时匹配到旧的）
        ControlLog cl = controlLogRepository
                .findTopByDeviceIdAndCommandAndResultIsNullOrderByCreatedAtDesc(deviceId, command);
        if (cl != null) {
            cl.setResult(result);
            controlLogRepository.save(cl);
            log.info("更新控制结果: deviceId={}, command={}, result={}", deviceId, command, result);
        } else {
            log.debug("未找到待更新的控制日志: deviceId={}, command={}", deviceId, command);
        }
        if ("success".equals(result)) {
            deviceRepository.findByDeviceId(deviceId).ifPresent(d -> {
                d.setLightStatus(command);
                deviceRepository.save(d);
            });
        }
    }

    @Override
    @Transactional
    public List<Map<String, Object>> sendBatchControlCommand(List<String> deviceIds,
                                                              String command, Integer brightness) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (String deviceId : deviceIds) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceId", deviceId);
            try {
                sendControlCommand(deviceId, command, "manual", brightness);
                item.put("result", "success");
            } catch (Exception e) {
                item.put("result", "fail");
                item.put("error", e.getMessage());
                log.error("批量控制失败 - deviceId={}, command={}: {}", deviceId, command, e.getMessage());
            }
            results.add(item);
        }
        log.info("批量控制完成: command={}, 总数={}, 成功={}",
                command, results.size(), results.stream().filter(r -> "success".equals(r.get("result"))).count());
        return results;
    }

    @Override
    public Page<ControlLog> getControlLogs(String deviceId, Pageable pageable) {
        return controlLogRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId, pageable);
    }

    @Override
    @Transactional
    public void setControlMode(Long id, String mode) {
        Device d = deviceRepository.findById(id)
                .orElseThrow(() -> new BusinessException("设备不存在, id=" + id));
        String newMode = mode.toLowerCase();
        d.setControlMode(newMode);
        deviceRepository.save(d);
        // ★ 推送设备状态变更（含新模式，前端实时更新）
        webSocketHandler.pushDeviceStatus(d.getDeviceId(), d.getStatus(), d.getLightStatus(), newMode);
        log.info("切换控制模式: id={}, mode={}", id, newMode);

        // ★ 通知传感器模式变更（否则传感器不知道模式切换，manual 模式下不会恢复自动上报）
        List<Sensor> lightSensors = sensorRepository
                .findBoundSensorsByDeviceIdAndType(d.getDeviceId(), "light");
        if (!lightSensors.isEmpty()) {
            // 用当前灯光状态作为 command，source=新模式，传感器收到后根据 source 切换内部模式
            String currentCmd = d.getLightStatus(); // "on" or "off"
            for (Sensor s : lightSensors) {
                Long targetSensorId = s.getSimulatorSensorId() != null
                        ? s.getSimulatorSensorId() : s.getId();
                mqttPublishService.publishCommand(targetSensorId, d.getDeviceId(),
                        currentCmd, newMode);
            }
            log.info("已通知 {} 个传感器模式变更: deviceId={}, mode={}",
                    lightSensors.size(), d.getDeviceId(), newMode);
        }
    }

    @Override
    @Transactional
    public void setThreshold(Long id, Double thresholdOn, Double thresholdOff) {
        if (thresholdOn >= thresholdOff) {
            throw new BusinessException("thresholdOn 必须小于 thresholdOff");
        }
        Device d = deviceRepository.findById(id)
                .orElseThrow(() -> new BusinessException("设备不存在, id=" + id));
        d.setThresholdOn(thresholdOn);
        d.setThresholdOff(thresholdOff);
        deviceRepository.save(d);
        log.info("更新阈值: id={}, thresholdOn={}, thresholdOff={}", id, thresholdOn, thresholdOff);
    }

    @Override
    @Transactional
    public void setSensorStrategy(Long deviceId, String sensorStrategy, Long primarySensorId) {
        Device d = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new BusinessException("设备不存在, id=" + deviceId));
        d.setSensorStrategy(sensorStrategy);
        d.setPrimarySensorId(primarySensorId);
        deviceRepository.save(d);
        log.info("更新传感器决策策略: id={}, strategy={}, primarySensorId={}",
                deviceId, sensorStrategy, primarySensorId);
    }

    @Override
    @Transactional
    public int batchSetThreshold(List<Long> ids, Double thresholdOn, Double thresholdOff) {
        if (thresholdOn >= thresholdOff) {
            throw new BusinessException("thresholdOn 必须小于 thresholdOff");
        }
        int count = 0;
        for (Long id : ids) {
            Device d = deviceRepository.findById(id).orElse(null);
            if (d != null) {
                d.setThresholdOn(thresholdOn);
                d.setThresholdOff(thresholdOff);
                deviceRepository.save(d);
                count++;
            }
        }
        log.info("批量更新阈值: {} 个设备, thresholdOn={}, thresholdOff={}", count, thresholdOn, thresholdOff);
        return count;
    }
}
