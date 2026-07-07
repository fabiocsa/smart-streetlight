package com.streetlight.service;

import com.streetlight.entity.ControlLog;
import com.streetlight.entity.Device;
import com.streetlight.mqtt.MqttClientManager;
import com.streetlight.repository.ControlLogRepository;
import com.streetlight.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ControlService {

    private final ControlLogRepository controlLogRepository;
    private final DeviceRepository deviceRepository;
    private final MqttClientManager mqttClientManager;

    @Transactional
    public ControlLog sendCommand(String deviceId, String command, String source) {
        // 更新设备灯光状态
        deviceRepository.findByDeviceId(deviceId).ifPresent(device -> {
            device.setLightStatus(command);
            deviceRepository.save(device);
        });

        // 记录控制日志
        ControlLog controlLog = ControlLog.builder()
                .deviceId(deviceId)
                .command(command)
                .source(source)
                .result("success")
                .build();
        ControlLog savedLog = controlLogRepository.save(controlLog);

        // 通过MQTT向设备下发控制指令
        mqttClientManager.publishControl(deviceId, command, source);
        log.info("MQTT控制指令已下发 - deviceId: {}, command: {}, source: {}", deviceId, command, source);

        return savedLog;
    }

    public List<ControlLog> getControlLogs(String deviceId) {
        return controlLogRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId);
    }

    /**
     * 更新控制日志的实际执行结果（由MQTT控制响应回调触发）
     */
    @Transactional
    public void updateControlLogResult(String deviceId, String command, String result) {
        List<ControlLog> logs = controlLogRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId);
        logs.stream()
                .filter(cl -> cl.getCommand().equals(command) && "success".equals(cl.getResult()))
                .findFirst()
                .ifPresent(cl -> {
                    cl.setResult(result);
                    controlLogRepository.save(cl);
                    log.info("更新控制日志结果 - deviceId: {}, command: {}, result: {}", deviceId, command, result);
                });
    }

    /**
     * 光照联动控制逻辑
     * auto模式下: 光照 < threshold_on 且灯关 → 开灯
     *            光照 > threshold_off 且灯开 → 关灯
     */
    @Transactional
    public String evaluateAutoControl(Device device, Double lightIntensity) {
        if (!"auto".equals(device.getControlMode())) {
            return null;
        }

        String command = null;
        if ("off".equals(device.getLightStatus()) && lightIntensity < device.getThresholdOn()) {
            command = "on";
        } else if ("on".equals(device.getLightStatus()) && lightIntensity > device.getThresholdOff()) {
            command = "off";
        }

        if (command != null) {
            sendCommand(device.getDeviceId(), command, "auto");
        }
        return command;
    }
}
