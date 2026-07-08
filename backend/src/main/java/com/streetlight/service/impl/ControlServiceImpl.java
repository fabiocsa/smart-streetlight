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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
        ControlLog cl = ControlLog.builder()
                .deviceId(deviceId).command(command).source(source).build();
        controlLogRepository.save(cl);
        mqttPublishService.publishCommand(deviceId, command, source);
        log.info("下发控制指令: deviceId={}, command={}, source={}", deviceId, command, source);
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
    public List<ControlLog> getControlLogs(String deviceId) {
        return controlLogRepository.findTop20ByDeviceIdOrderByCreatedAtDesc(deviceId);
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
