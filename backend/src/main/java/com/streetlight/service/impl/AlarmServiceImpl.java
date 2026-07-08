package com.streetlight.service.impl;

import com.streetlight.entity.AlarmLog;
import com.streetlight.enums.AlarmSeverity;
import com.streetlight.enums.AlarmStatus;
import com.streetlight.enums.AlarmType;
import com.streetlight.repository.AlarmLogRepository;
import com.streetlight.service.AlarmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlarmServiceImpl implements AlarmService {

    private final AlarmLogRepository alarmLogRepository;

    @Override
    @Transactional
    public void createOfflineAlarm(String deviceId) {
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
    }

    @Override
    @Transactional
    public void autoResolveOfflineAlarm(String deviceId) {
        List<AlarmLog> pending = alarmLogRepository.findByDeviceIdAndStatus(deviceId, AlarmStatus.PENDING);
        for (AlarmLog alarm : pending) {
            alarm.setStatus(AlarmStatus.RESOLVED);
            alarm.setResolvedAt(LocalDateTime.now());
            alarm.setResolvedBy("system");
        }
        alarmLogRepository.saveAll(pending);
        if (!pending.isEmpty()) {
            log.info("自动解决离线告警: deviceId={}, count={}", deviceId, pending.size());
        }
    }

    @Override
    public Page<AlarmLog> listAlarms(int page, int size, String status, String type, String deviceId) {
        PageRequest pr = PageRequest.of(page, size);
        if (deviceId != null) {
            if (status != null && type != null) {
                return alarmLogRepository.findByDeviceIdAndStatusAndAlarmType(
                        deviceId, AlarmStatus.valueOf(status.toUpperCase()), AlarmType.valueOf(type.toUpperCase()), pr);
            } else if (status != null) {
                return alarmLogRepository.findByDeviceIdAndStatus(deviceId, AlarmStatus.valueOf(status.toUpperCase()), pr);
            } else if (type != null) {
                return alarmLogRepository.findByDeviceIdAndAlarmType(deviceId, AlarmType.valueOf(type.toUpperCase()), pr);
            }
            return alarmLogRepository.findByDeviceId(deviceId, pr);
        }
        if (status != null && type != null) {
            return alarmLogRepository.findByStatusAndAlarmType(
                    AlarmStatus.valueOf(status.toUpperCase()),
                    AlarmType.valueOf(type.toUpperCase()), pr);
        } else if (status != null) {
            return alarmLogRepository.findByStatus(AlarmStatus.valueOf(status.toUpperCase()), pr);
        } else if (type != null) {
            return alarmLogRepository.findByAlarmType(AlarmType.valueOf(type.toUpperCase()), pr);
        }
        return alarmLogRepository.findAll(pr);
    }

    @Override
    @Transactional
    public void resolveAlarm(Long alarmId, String resolvedBy) {
        AlarmLog alarm = alarmLogRepository.findById(alarmId)
                .orElseThrow(() -> new RuntimeException("告警不存在, id=" + alarmId));
        alarm.setStatus(AlarmStatus.RESOLVED);
        alarm.setResolvedAt(LocalDateTime.now());
        alarm.setResolvedBy(resolvedBy);
        alarmLogRepository.save(alarm);
        log.info("处理告警: id={}, resolvedBy={}", alarmId, resolvedBy);
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
