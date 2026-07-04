package com.streetlight.service;

import com.streetlight.entity.ControlLog;
import com.streetlight.entity.Device;
import com.streetlight.repository.ControlLogRepository;
import com.streetlight.repository.DeviceRepository;
import com.streetlight.websocket.WebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ControlService {

    private final ControlLogRepository controlLogRepository;
    private final DeviceRepository deviceRepository;
    private final WebSocketHandler webSocketHandler;

    @Transactional
    public ControlLog sendCommand(String deviceId, String command, String source) {
        // 更新设备灯光状态
        deviceRepository.findByDeviceId(deviceId).ifPresent(device -> {
            device.setLightStatus(command);
            deviceRepository.save(device);

            // WebSocket 推送设备状态变更
            webSocketHandler.pushDeviceStatus(deviceId, device.getStatus(), command);
        });

        // 记录控制日志
        ControlLog log = ControlLog.builder()
                .deviceId(deviceId)
                .command(command)
                .source(source)
                .result("success")
                .build();
        ControlLog savedLog = controlLogRepository.save(log);

        // WebSocket 推送控制结果
        webSocketHandler.pushControlResult(deviceId, command, "success");

        return savedLog;
    }

    public List<ControlLog> getControlLogs(String deviceId) {
        return controlLogRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId);
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
