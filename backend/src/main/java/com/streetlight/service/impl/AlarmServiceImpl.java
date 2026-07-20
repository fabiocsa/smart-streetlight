package com.streetlight.service.impl;

import com.streetlight.common.BusinessException;
import com.streetlight.entity.AlarmLog;
import com.streetlight.enums.AlarmSeverity;
import com.streetlight.enums.AlarmStatus;
import com.streetlight.enums.AlarmType;
import com.streetlight.enums.AssignmentMode;
import com.streetlight.repository.AlarmLogRepository;
import com.streetlight.service.AlarmService;
import com.streetlight.service.HandlerService;
import com.streetlight.websocket.WebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlarmServiceImpl implements AlarmService {

    private final AlarmLogRepository alarmLogRepository;
    private final WebSocketHandler webSocketHandler;
    private final HandlerService handlerService;

    @Lazy
    @Autowired
    private AlarmServiceImpl self;  // 自注入，用于批量操作中每个子项独立事务

    @Override
    @Transactional
    public void createOfflineAlarm(String deviceId) {
        // 防重复：已有未处理的离线告警则不重复创建
        List<AlarmLog> existing = alarmLogRepository.findByDeviceIdAndStatus(deviceId, AlarmStatus.PENDING);
        boolean hasOfflinePending = existing.stream()
                .anyMatch(a -> a.getAlarmType() == AlarmType.OFFLINE);
        if (hasOfflinePending) {
            return;
        }

        AssignmentMode currentMode = handlerService.getAssignmentMode();

        if (currentMode == AssignmentMode.AUTO) {
            // 自动模式：创建告警 → 自动分配处理人 → 直接标记为已解决
            AlarmLog alarm = AlarmLog.builder()
                    .deviceId(deviceId)
                    .alarmType(AlarmType.OFFLINE)
                    .content("设备 " + deviceId + " 已离线超过30秒")
                    .severity(AlarmSeverity.WARNING)
                    .status(AlarmStatus.PENDING)
                    .assignmentMode(currentMode)
                    .build();
            alarmLogRepository.save(alarm);
            log.warn("创建离线告警: deviceId={}, mode=AUTO", deviceId);

            try {
                handlerService.autoAssignHandler(alarm);
                log.info("自动模式: 告警已分配处理人并解决, alarmId={}", alarm.getId());
            } catch (BusinessException e) {
                log.warn("自动分配失败（告警保持 PENDING）: alarmId={}, error={}", alarm.getId(), e.getMessage());
            }
        } else {
            // 手动模式：创建告警保持 PENDING, 推送到前端等待手动分配
            AlarmLog alarm = AlarmLog.builder()
                    .deviceId(deviceId)
                    .alarmType(AlarmType.OFFLINE)
                    .content("设备 " + deviceId + " 已离线超过30秒")
                    .severity(AlarmSeverity.WARNING)
                    .status(AlarmStatus.PENDING)
                    .assignmentMode(currentMode)
                    .build();
            alarmLogRepository.save(alarm);
            log.warn("创建离线告警: deviceId={}, mode=MANUAL", deviceId);

            webSocketHandler.pushNewAlarm(
                    alarm.getId(),
                    deviceId,
                    "offline",
                    alarm.getContent(),
                    "warning"
            );
        }
    }

    @Override
    @Transactional
    public void autoResolveOfflineAlarm(String deviceId) {
        List<AlarmLog> pending = alarmLogRepository.findByDeviceIdAndStatus(deviceId, AlarmStatus.PENDING);
        // 仅自动解决 OFFLINE 类型告警，不误关其他类型的待处理告警（如 ABNORMAL_DATA）
        // 同时只解除自动模式创建的告警，手动模式的告警需管理员手动处理
        List<AlarmLog> offlineAlarms = pending.stream()
                .filter(a -> a.getAlarmType() == AlarmType.OFFLINE)
                .filter(a -> a.getAssignmentMode() == AssignmentMode.AUTO || a.getAssignmentMode() == null)
                .toList();
        if (offlineAlarms.isEmpty()) {
            return;
        }
        for (AlarmLog alarm : offlineAlarms) {
            alarm.setStatus(AlarmStatus.RESOLVED);
            alarm.setResolvedAt(LocalDateTime.now());
            alarm.setNotes("设备恢复在线，自动解决");

            // 释放分配的处理人（若有）
            if (alarm.getAssignedHandlerId() != null) {
                handlerService.releaseHandler(alarm.getAssignedHandlerId());
            }
        }
        alarmLogRepository.saveAll(offlineAlarms);
        log.info("自动解决离线告警: deviceId={}, count={}", deviceId, offlineAlarms.size());
    }

    @Override
    public Page<AlarmLog> listAlarms(int page, int size, String status, String type,
                                      String severity, String deviceId, String keyword,
                                      String sort, String order) {
        Sort.Direction dir;
        if (order != null && (order.equalsIgnoreCase("asc") || order.equalsIgnoreCase("ascending"))) {
            dir = Sort.Direction.ASC;
        } else {
            dir = Sort.Direction.DESC;
        }
        String sortField = (sort != null && !sort.isEmpty()) ? sort : "createdAt";
        PageRequest pr = PageRequest.of(page, size, Sort.by(dir, sortField));

        // 多条件组合查询 — 优先使用最具体的查询
        if (deviceId != null && !deviceId.isEmpty()) {
            return alarmLogRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId, pr);
        }
        if (status != null && severity != null) {
            return alarmLogRepository.findBySeverityAndStatus(
                    AlarmSeverity.valueOf(severity.toUpperCase()),
                    AlarmStatus.valueOf(status.toUpperCase()), pr);
        }
        if (status != null && type != null) {
            return alarmLogRepository.findByStatusAndAlarmType(
                    AlarmStatus.valueOf(status.toUpperCase()),
                    AlarmType.valueOf(type.toUpperCase()), pr);
        }
        if (severity != null && type != null) {
            // 新增：按严重级别+类型组合（复用已有查询模式）
            // 目前无直接的复合查询，使用 findAll 兜底
            return alarmLogRepository.findAll(pr);
        }
        if (status != null) {
            return alarmLogRepository.findByStatus(AlarmStatus.valueOf(status.toUpperCase()), pr);
        }
        if (type != null) {
            return alarmLogRepository.findByAlarmType(AlarmType.valueOf(type.toUpperCase()), pr);
        }
        if (severity != null) {
            return alarmLogRepository.findBySeverity(AlarmSeverity.valueOf(severity.toUpperCase()), pr);
        }
        return alarmLogRepository.findAll(pr);
    }

    @Override
    @Transactional
    public void resolveAlarm(Long alarmId, String resolvedBy) {
        resolveAlarm(alarmId, resolvedBy, null);
    }

    @Override
    @Transactional
    public void resolveAlarm(Long alarmId, String resolvedBy, String notes) {
        AlarmLog alarm = alarmLogRepository.findById(alarmId)
                .orElseThrow(() -> new RuntimeException("告警不存在, id=" + alarmId));
        alarm.setStatus(AlarmStatus.RESOLVED);
        alarm.setResolvedAt(LocalDateTime.now());
        alarm.setResolvedBy(resolvedBy);
        if (notes != null && !notes.isBlank()) {
            alarm.setNotes(notes);
        }
        alarmLogRepository.save(alarm);
        log.info("处理告警: id={}, resolvedBy={}", alarmId, resolvedBy);
    }

    @Override
    @Transactional
    public void updateResolvedBy(Long alarmId, String resolvedBy) {
        AlarmLog alarm = alarmLogRepository.findById(alarmId)
                .orElseThrow(() -> new RuntimeException("告警不存在, id=" + alarmId));
        alarm.setResolvedBy(resolvedBy);
        alarmLogRepository.save(alarm);
        log.info("修改告警处理人: id={}, resolvedBy={}", alarmId, resolvedBy);
    }

    @Override
    public Map<String, Object> batchUpdateResolvedBy(List<Long> alarmIds, String resolvedBy) {
        int success = 0, fail = 0;
        for (Long id : alarmIds) {
            try {
                self.updateResolvedBy(id, resolvedBy);  // 通过代理调用，每个子项独立事务
                success++;
            } catch (Exception e) {
                fail++;
                log.warn("批量修改处理人失败: id={}, {}", id, e.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", alarmIds.size());
        result.put("success", success);
        result.put("fail", fail);
        return result;
    }

    @Override
    public Map<String, Object> batchResolve(List<Long> alarmIds, String resolvedBy, String notes) {
        int success = 0, fail = 0;
        for (Long id : alarmIds) {
            try {
                self.resolveAlarm(id, resolvedBy, notes);  // 通过代理调用，每个子项独立事务
                success++;
            } catch (Exception e) {
                fail++;
                log.warn("批量处理告警失败: id={}, {}", id, e.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", alarmIds.size());
        result.put("success", success);
        result.put("fail", fail);
        return result;
    }

    @Override
    public long countPendingAlarms() {
        return alarmLogRepository.countByStatus(AlarmStatus.PENDING);
    }

    @Override
    public long countTodayAlarms() {
        LocalDateTime start = LocalDate.now().atStartOfDay();
        LocalDateTime end = LocalDate.now().atTime(LocalTime.MAX);
        return alarmLogRepository.countByCreatedAtBetween(start, end);
    }

    @Override
    @Transactional
    public void assignHandler(Long alarmId, Long handlerId) {
        handlerService.manualAssignHandler(alarmId, handlerId);
    }

    @Override
    @Transactional
    public Map<String, Object> batchAutoAssignPending() {
        List<AlarmLog> pendingAlarms = alarmLogRepository.findByStatus(AlarmStatus.PENDING);
        if (pendingAlarms.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("total", 0);
            empty.put("success", 0);
            empty.put("fail", 0);
            return empty;
        }
        int success = 0, fail = 0;
        for (AlarmLog alarm : pendingAlarms) {
            try {
                handlerService.autoAssignHandler(alarm);
                success++;
            } catch (BusinessException e) {
                fail++;
                log.warn("自动分配失败: alarmId={}, error={}", alarm.getId(), e.getMessage());
            }
        }
        log.info("批量自动分配完成: 总数={}, 成功={}, 失败={}", pendingAlarms.size(), success, fail);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pendingAlarms.size());
        result.put("success", success);
        result.put("fail", fail);
        return result;
    }

    // ==================== 电压异常告警 ====================

    @Override
    @Transactional
    public void createVoltageAbnormalAlarm(String deviceId, Double voltage, Double min, Double max) {
        // 防重复：已有未处理的电压异常告警则不重复创建
        List<AlarmLog> existing = alarmLogRepository.findByDeviceIdAndStatus(deviceId, AlarmStatus.PENDING);
        boolean hasVoltagePending = existing.stream()
                .anyMatch(a -> a.getAlarmType() == AlarmType.VOLTAGE_ABNORMAL);
        if (hasVoltagePending) {
            log.info("[告警创建] 电压异常告警已存在(去重跳过): deviceId={}, pendingTypes={}",
                    deviceId, existing.stream().map(a -> a.getAlarmType().name()).toList());
            return;
        }
        log.info("[告警创建] 开始创建电压异常告警: deviceId={}, voltage={}, min={}, max={}", deviceId, voltage, min, max);

        String content;
        if (voltage > max) {
            content = "设备 " + deviceId + " 电压过高: " + String.format("%.1f", voltage) + "V (正常范围: " + min + "V ~ " + max + "V)";
        } else {
            content = "设备 " + deviceId + " 电压过低: " + String.format("%.1f", voltage) + "V (正常范围: " + min + "V ~ " + max + "V)";
        }

        AssignmentMode currentMode = handlerService.getAssignmentMode();

        AlarmLog alarm = AlarmLog.builder()
                .deviceId(deviceId)
                .alarmType(AlarmType.VOLTAGE_ABNORMAL)
                .content(content)
                .severity(AlarmSeverity.WARNING)
                .status(AlarmStatus.PENDING)
                .assignmentMode(currentMode)
                .build();
        alarmLogRepository.save(alarm);
        log.warn("创建电压异常告警: deviceId={}, voltage={}, range=[{}~{}]", deviceId, voltage, min, max);

        if (currentMode == AssignmentMode.AUTO) {
            try {
                handlerService.autoAssignHandler(alarm);
                log.info("自动模式: 电压告警已分配处理人并解决, alarmId={}", alarm.getId());
            } catch (BusinessException e) {
                log.warn("自动分配失败（告警保持 PENDING）: alarmId={}, error={}", alarm.getId(), e.getMessage());
            }
        } else {
            webSocketHandler.pushNewAlarm(alarm.getId(), deviceId, "voltage_abnormal", alarm.getContent(), "warning");
        }
    }

    @Override
    @Transactional
    public void autoResolveVoltageAlarm(String deviceId) {
        List<AlarmLog> pending = alarmLogRepository.findByDeviceIdAndStatus(deviceId, AlarmStatus.PENDING);
        List<AlarmLog> voltageAlarms = pending.stream()
                .filter(a -> a.getAlarmType() == AlarmType.VOLTAGE_ABNORMAL)
                .filter(a -> a.getAssignmentMode() == null || a.getAssignmentMode() == AssignmentMode.AUTO)
                .toList();
        if (voltageAlarms.isEmpty()) {
            return;
        }
        for (AlarmLog alarm : voltageAlarms) {
            alarm.setStatus(AlarmStatus.RESOLVED);
            alarm.setResolvedAt(LocalDateTime.now());
            alarm.setNotes("电压恢复正常，自动解决");
            if (alarm.getAssignedHandlerId() != null) {
                handlerService.releaseHandler(alarm.getAssignedHandlerId());
            }
        }
        alarmLogRepository.saveAll(voltageAlarms);
        log.info("自动解决电压告警: deviceId={}, count={}", deviceId, voltageAlarms.size());
    }

    // ==================== 温度过高告警 ====================

    @Override
    @Transactional
    public void createTemperatureHighAlarm(String deviceId, Double temperature, Double max) {
        // 防重复：已有未处理的温度过高告警则不重复创建
        List<AlarmLog> existing = alarmLogRepository.findByDeviceIdAndStatus(deviceId, AlarmStatus.PENDING);
        boolean hasTempPending = existing.stream()
                .anyMatch(a -> a.getAlarmType() == AlarmType.TEMPERATURE_HIGH);
        if (hasTempPending) {
            return;
        }

        String content = "设备 " + deviceId + " 温度过高: " + String.format("%.1f", temperature)
                + "°C (正常上限: " + String.format("%.1f", max) + "°C)";

        AssignmentMode currentMode = handlerService.getAssignmentMode();

        AlarmLog alarm = AlarmLog.builder()
                .deviceId(deviceId)
                .alarmType(AlarmType.TEMPERATURE_HIGH)
                .content(content)
                .severity(AlarmSeverity.WARNING)
                .status(AlarmStatus.PENDING)
                .assignmentMode(currentMode)
                .build();
        alarmLogRepository.save(alarm);
        log.warn("创建温度过高告警: deviceId={}, temperature={}, max={}", deviceId, temperature, max);

        if (currentMode == AssignmentMode.AUTO) {
            try {
                handlerService.autoAssignHandler(alarm);
                log.info("自动模式: 温度告警已分配处理人并解决, alarmId={}", alarm.getId());
            } catch (BusinessException e) {
                log.warn("自动分配失败（告警保持 PENDING）: alarmId={}, error={}", alarm.getId(), e.getMessage());
            }
        } else {
            webSocketHandler.pushNewAlarm(alarm.getId(), deviceId, "temperature_high", alarm.getContent(), "warning");
        }
    }

    @Override
    @Transactional
    public void autoResolveTemperatureAlarm(String deviceId) {
        List<AlarmLog> pending = alarmLogRepository.findByDeviceIdAndStatus(deviceId, AlarmStatus.PENDING);
        List<AlarmLog> tempAlarms = pending.stream()
                .filter(a -> a.getAlarmType() == AlarmType.TEMPERATURE_HIGH)
                .filter(a -> a.getAssignmentMode() == null || a.getAssignmentMode() == AssignmentMode.AUTO)
                .toList();
        if (tempAlarms.isEmpty()) {
            return;
        }
        for (AlarmLog alarm : tempAlarms) {
            alarm.setStatus(AlarmStatus.RESOLVED);
            alarm.setResolvedAt(LocalDateTime.now());
            alarm.setNotes("温度恢复正常，自动解决");
            if (alarm.getAssignedHandlerId() != null) {
                handlerService.releaseHandler(alarm.getAssignedHandlerId());
            }
        }
        alarmLogRepository.saveAll(tempAlarms);
        log.info("自动解决温度告警: deviceId={}, count={}", deviceId, tempAlarms.size());
    }

    // ==================== 功率过高告警 ====================

    @Override
    @Transactional
    public void createPowerHighAlarm(String deviceId, Double power, Double max) {
        // 防重复：已有未处理的功率过高告警则不重复创建
        List<AlarmLog> existing = alarmLogRepository.findByDeviceIdAndStatus(deviceId, AlarmStatus.PENDING);
        boolean hasPowerPending = existing.stream()
                .anyMatch(a -> a.getAlarmType() == AlarmType.POWER_HIGH);
        if (hasPowerPending) {
            return;
        }

        String content = "设备 " + deviceId + " 功率过高: " + String.format("%.1f", power)
                + "W (正常上限: " + String.format("%.1f", max) + "W)";

        AssignmentMode currentMode = handlerService.getAssignmentMode();

        AlarmLog alarm = AlarmLog.builder()
                .deviceId(deviceId)
                .alarmType(AlarmType.POWER_HIGH)
                .content(content)
                .severity(AlarmSeverity.WARNING)
                .status(AlarmStatus.PENDING)
                .assignmentMode(currentMode)
                .build();
        alarmLogRepository.save(alarm);
        log.warn("创建功率过高告警: deviceId={}, power={}, max={}", deviceId, power, max);

        if (currentMode == AssignmentMode.AUTO) {
            try {
                handlerService.autoAssignHandler(alarm);
                log.info("自动模式: 功率告警已分配处理人并解决, alarmId={}", alarm.getId());
            } catch (BusinessException e) {
                log.warn("自动分配失败（告警保持 PENDING）: alarmId={}, error={}", alarm.getId(), e.getMessage());
            }
        } else {
            webSocketHandler.pushNewAlarm(alarm.getId(), deviceId, "power_high", alarm.getContent(), "warning");
        }
    }

    @Override
    @Transactional
    public void autoResolvePowerAlarm(String deviceId) {
        List<AlarmLog> pending = alarmLogRepository.findByDeviceIdAndStatus(deviceId, AlarmStatus.PENDING);
        List<AlarmLog> powerAlarms = pending.stream()
                .filter(a -> a.getAlarmType() == AlarmType.POWER_HIGH)
                .filter(a -> a.getAssignmentMode() == null || a.getAssignmentMode() == AssignmentMode.AUTO)
                .toList();
        if (powerAlarms.isEmpty()) {
            return;
        }
        for (AlarmLog alarm : powerAlarms) {
            alarm.setStatus(AlarmStatus.RESOLVED);
            alarm.setResolvedAt(LocalDateTime.now());
            alarm.setNotes("功率恢复正常，自动解决");
            if (alarm.getAssignedHandlerId() != null) {
                handlerService.releaseHandler(alarm.getAssignedHandlerId());
            }
        }
        alarmLogRepository.saveAll(powerAlarms);
        log.info("自动解决功率告警: deviceId={}, count={}", deviceId, powerAlarms.size());
    }
}
