package com.streetlight.service;

import com.streetlight.entity.AlarmLog;
import com.streetlight.repository.AlarmLogRepository;
import lombok.RequiredArgsConstructor;
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
public class AlarmService {

    private final AlarmLogRepository alarmLogRepository;

    public AlarmLog createAlarm(AlarmLog alarm) {
        return alarmLogRepository.save(alarm);
    }

    public Page<AlarmLog> getAlarms(String status, String alarmType, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        if (status != null && alarmType != null) {
            return alarmLogRepository.findByStatusAndAlarmType(status, alarmType, pageRequest);
        } else if (status != null) {
            return alarmLogRepository.findByStatus(status, pageRequest);
        } else if (alarmType != null) {
            return alarmLogRepository.findByAlarmType(alarmType, pageRequest);
        }
        return alarmLogRepository.findAll(pageRequest);
    }

    @Transactional
    public AlarmLog resolveAlarm(Long id, String resolvedBy) {
        return alarmLogRepository.findById(id).map(alarm -> {
            alarm.setStatus("resolved");
            alarm.setResolvedAt(LocalDateTime.now());
            alarm.setResolvedBy(resolvedBy);
            return alarmLogRepository.save(alarm);
        }).orElseThrow(() -> new RuntimeException("告警不存在, id=" + id));
    }

    @Transactional
    public void resolveAlarmsByDeviceId(String deviceId) {
        List<AlarmLog> pendingAlarms = alarmLogRepository.findByDeviceIdAndStatus(deviceId, "pending");
        for (AlarmLog alarm : pendingAlarms) {
            alarm.setStatus("resolved");
            alarm.setResolvedAt(LocalDateTime.now());
            alarm.setResolvedBy("system");
        }
        alarmLogRepository.saveAll(pendingAlarms);
    }

    public long countPendingAlarms() {
        return alarmLogRepository.countByStatus("pending");
    }

    public long countTodayAlarms() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime todayEnd = LocalDate.now().atTime(LocalTime.MAX);
        return alarmLogRepository.countByCreatedAtBetween(todayStart, todayEnd);
    }
}
