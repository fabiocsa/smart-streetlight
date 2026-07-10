package com.streetlight.service;

import com.streetlight.entity.AlarmLog;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Map;

public interface AlarmService {

    void createOfflineAlarm(String deviceId);

    void autoResolveOfflineAlarm(String deviceId);

    Page<AlarmLog> listAlarms(int page, int size, String status, String type, String severity,
                              String deviceId, String keyword, String sort, String order);

    void resolveAlarm(Long alarmId, String resolvedBy);

    void resolveAlarm(Long alarmId, String resolvedBy, String notes);

    void updateResolvedBy(Long alarmId, String resolvedBy);

    Map<String, Object> batchResolve(List<Long> alarmIds, String resolvedBy, String notes);

    long countPendingAlarms();

    long countTodayAlarms();
}
