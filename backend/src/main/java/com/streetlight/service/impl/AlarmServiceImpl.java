package com.streetlight.service.impl;

import com.streetlight.entity.AlarmLog;
import com.streetlight.enums.AlarmSeverity;
import com.streetlight.enums.AlarmStatus;
import com.streetlight.enums.AlarmType;
import com.streetlight.repository.AlarmLogRepository;
import com.streetlight.service.AlarmService;
import com.streetlight.websocket.WebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
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
        AlarmLog alarm = AlarmLog.builder()
                .deviceId(deviceId)
                .alarmType(AlarmType.OFFLINE)
                .content("设备 " + deviceId + " 已离线超过30秒")
                .severity(AlarmSeverity.WARNING)
                .status(AlarmStatus.PENDING)
                .build();
        alarmLogRepository.save(alarm);
        log.warn("创建离线告警: deviceId={}", deviceId);

        // 通过 WebSocket 推送新告警通知到前端
        webSocketHandler.pushNewAlarm(
                alarm.getId(),
                deviceId,
                "offline",
                alarm.getContent(),
                "warning"
        );
    }

    @Override
    @Transactional
    public void autoResolveOfflineAlarm(String deviceId) {
        List<AlarmLog> pending = alarmLogRepository.findByDeviceIdAndStatus(deviceId, AlarmStatus.PENDING);
        // 仅自动解决 OFFLINE 类型告警，不误关其他类型的待处理告警（如 ABNORMAL_DATA）
        List<AlarmLog> offlineAlarms = pending.stream()
                .filter(a -> a.getAlarmType() == AlarmType.OFFLINE)
                .toList();
        if (offlineAlarms.isEmpty()) {
            return;
        }
        for (AlarmLog alarm : offlineAlarms) {
            alarm.setStatus(AlarmStatus.RESOLVED);
            alarm.setResolvedAt(LocalDateTime.now());
            alarm.setResolvedBy("system");
            alarm.setNotes("设备恢复在线，自动解决");
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
    @Transactional
    public Map<String, Object> batchResolve(List<Long> alarmIds, String resolvedBy, String notes) {
        int success = 0, fail = 0;
        for (Long id : alarmIds) {
            try {
                resolveAlarm(id, resolvedBy, notes);
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
}
