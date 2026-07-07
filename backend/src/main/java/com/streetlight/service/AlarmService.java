package com.streetlight.service;

import com.streetlight.entity.AlarmLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AlarmService {

    void createOfflineAlarm(String deviceId);

    void autoResolveOfflineAlarm(String deviceId);

    Page<AlarmLog> listAlarms(Pageable pageable, String status, String type);

    void resolveAlarm(Long alarmId, String resolvedBy);

    long countPendingAlarms();

    long countTodayAlarms();
}
