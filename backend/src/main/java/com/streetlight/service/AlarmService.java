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

    Map<String, Object> batchUpdateResolvedBy(List<Long> alarmIds, String resolvedBy);

    Map<String, Object> batchResolve(List<Long> alarmIds, String resolvedBy, String notes);

    long countPendingAlarms();

    long countTodayAlarms();

    /** 手工分配处理人并解决告警 */
    void assignHandler(Long alarmId, Long handlerId);

    /** 批量自动分配：处理所有 PENDING 告警 */
    Map<String, Object> batchAutoAssignPending();

    /** 创建电压异常告警（含防重复） */
    void createVoltageAbnormalAlarm(String deviceId, Double voltage, Double min, Double max);

    /** 电压恢复时自动解除电压异常告警 */
    void autoResolveVoltageAlarm(String deviceId);

    /** 创建温度过高告警（含防重复） */
    void createTemperatureHighAlarm(String deviceId, Double temperature, Double max);

    /** 温度恢复时自动解除温度过高告警 */
    void autoResolveTemperatureAlarm(String deviceId);

    /** 创建功率过高告警（含防重复） */
    void createPowerHighAlarm(String deviceId, Double power, Double max);

    /** 功率恢复时自动解除功率过高告警 */
    void autoResolvePowerAlarm(String deviceId);
}
