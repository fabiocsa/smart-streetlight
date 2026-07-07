package com.streetlight.service;

import com.streetlight.entity.Device;

public interface ControlService {

    void sendControlCommand(String deviceId, String command, String source);

    void updateControlResult(String deviceId, String command, String result);

    void setControlMode(Long deviceId, String mode);

    void setThreshold(Long deviceId, Double thresholdOn, Double thresholdOff);

    /**
     * 光照联动控制逻辑
     * auto模式下: 光照 < threshold_on 且灯关 → 开灯
     *            光照 > threshold_off 且灯开 → 关灯
     */
    String evaluateAutoControl(Device device, Double lightIntensity);
}
