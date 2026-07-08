package com.streetlight.service;

import com.streetlight.entity.ControlLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

public interface ControlService {

    void sendControlCommand(String deviceId, String command, String source);

    void sendControlCommand(String deviceId, String command, String source, Integer brightness);

    void updateControlResult(String deviceId, String command, String result);

    void setControlMode(Long deviceId, String mode);

    void setThreshold(Long deviceId, Double thresholdOn, Double thresholdOff);

    List<Map<String, Object>> sendBatchControlCommand(List<String> deviceIds, String command, Integer brightness);

    Page<ControlLog> getControlLogs(String deviceId, Pageable pageable);
}
