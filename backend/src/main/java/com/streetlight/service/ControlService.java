package com.streetlight.service;

import com.streetlight.entity.ControlLog;

import java.util.List;

public interface ControlService {

    void sendControlCommand(String deviceId, String command, String source);

    void updateControlResult(String deviceId, String command, String result);

    List<ControlLog> getControlLogs(String deviceId);

    void setControlMode(Long deviceId, String mode);

    void setThreshold(Long deviceId, Double thresholdOn, Double thresholdOff);
}
