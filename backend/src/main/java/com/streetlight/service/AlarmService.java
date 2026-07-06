package com.streetlight.service;

import com.streetlight.entity.AlarmLog;
import org.springframework.data.domain.Page;

public interface AlarmService {

    void createOfflineAlarm(String deviceId);

    void autoResolveOfflineAlarm(String deviceId);

    Page<AlarmLog> listAlarms(int page, int size, String status, String type);

    void resolveAlarm(Long alarmId, String resolvedBy);

    long countPendingAlarms();

    long countTodayAlarms();
}
