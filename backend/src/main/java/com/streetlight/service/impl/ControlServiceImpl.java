package com.streetlight.service.impl;

import com.streetlight.common.BusinessException;
import com.streetlight.entity.ControlLog;
import com.streetlight.entity.Device;
import com.streetlight.mqtt.MqttPublishService;
import com.streetlight.repository.ControlLogRepository;
import com.streetlight.repository.DeviceRepository;
import com.streetlight.service.ControlService;
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
    private final MqttPublishService mqttPublishService;

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

        // ★ 必须先切换模式再发布 MQTT，消除竞态窗口：
        //   若先发 MQTT，模拟器可能在线程切换瞬间上报传感器数据，
        //   后端 saveAndAutoControl() 此时读到 controlMode 仍为 "auto" 会导致自动关灯。
        if ("manual".equals(source)) {
            deviceRepository.findByDeviceId(deviceId).ifPresent(d -> {
                d.setControlMode("manual");
                d.setBrightness(brightness);
                deviceRepository.save(d);
                log.info("手动控制已切换设备为手动模式: deviceId={}", deviceId);
            });
        } else if (brightness != null) {
            deviceRepository.findByDeviceId(deviceId).ifPresent(d -> {
                d.setBrightness(brightness);
                deviceRepository.save(d);
            });
        }

        // 通过 MQTT 下发指令（在模式切换之后）
        mqttPublishService.publishCommand(deviceId, command, source, brightness);

        log.info("下发控制指令: deviceId={}, command={}, source={}, brightness={}",
                deviceId, command, source, brightness);
    }

    @Override
    @Transactional
    public void updateControlResult(String deviceId, String command, String result) {
        ControlLog cl = controlLogRepository
                .findByDeviceIdAndCommandAndResultIsNull(deviceId, command);
        if (cl != null) {
            cl.setResult(result);
            controlLogRepository.save(cl);
            log.info("更新控制结果: deviceId={}, command={}, result={}", deviceId, command, result);
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
        d.setControlMode(mode.toLowerCase());
        deviceRepository.save(d);
        log.info("切换控制模式: id={}, mode={}", id, mode);
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
}
