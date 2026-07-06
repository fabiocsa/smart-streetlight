package com.streetlight.service;

public interface ControlService {

    void sendControlCommand(String deviceId, String command, String source);

    void updateControlResult(String deviceId, String command, String result);

    void setControlMode(Long deviceId, String mode);

    void setThreshold(Long deviceId, Double thresholdOn, Double thresholdOff);
}
