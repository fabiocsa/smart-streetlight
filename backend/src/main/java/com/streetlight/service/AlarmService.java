package com.streetlight.service;

import com.streetlight.entity.AlarmLog;
import com.streetlight.repository.AlarmLogRepository;
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
public class AlarmService {

    private final AlarmLogRepository alarmLogRepository;

    public AlarmLog createAlarm(AlarmLog alarm) {
        AlarmLog saved = alarmLogRepository.save(alarm);
        log.info("告警已创建 - id: {}, deviceId: {}, type: {}, severity: {}",
                saved.getId(), saved.getDeviceId(), saved.getAlarmType(), saved.getSeverity());
        return saved;
    }

    public Page<AlarmLog> getAlarms(String status, String alarmType, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        Page<AlarmLog> result;
        if (status != null && alarmType != null) {
            result = alarmLogRepository.findByStatusAndAlarmType(status, alarmType, pageRequest);
        } else if (status != null) {
            result = alarmLogRepository.findByStatus(status, pageRequest);
        } else if (alarmType != null) {
            result = alarmLogRepository.findByAlarmType(alarmType, pageRequest);
        } else {
            result = alarmLogRepository.findAll(pageRequest);
        }
        log.debug("查询告警列表 - status: {}, type: {}, page: {}, total: {}",
                status, alarmType, page, result.getTotalElements());
        return result;
    }

    @Transactional
    public AlarmLog resolveAlarm(Long id, String resolvedBy) {
        return alarmLogRepository.findById(id).map(alarm -> {
            alarm.setStatus("resolved");
            alarm.setResolvedAt(LocalDateTime.now());
            alarm.setResolvedBy(resolvedBy);
            AlarmLog saved = alarmLogRepository.save(alarm);
            log.info("告警已处理 - id: {}, resolvedBy: {}", id, resolvedBy);
            return saved;
        }).orElseThrow(() -> {
            log.error("处理告警失败，告警不存在 - id: {}", id);
            return new RuntimeException("告警不存在, id=" + id);
        });
    }

    @Transactional
    public void resolveAlarmsByDeviceId(String deviceId) {
        List<AlarmLog> pendingAlarms = alarmLogRepository.findByDeviceIdAndStatus(deviceId, "pending");
        if (!pendingAlarms.isEmpty()) {
            for (AlarmLog alarm : pendingAlarms) {
                alarm.setStatus("resolved");
                alarm.setResolvedAt(LocalDateTime.now());
                alarm.setResolvedBy("system");
            }
            alarmLogRepository.saveAll(pendingAlarms);
            log.info("设备离线告警已自动关闭 - deviceId: {}, count: {}", deviceId, pendingAlarms.size());
        }
    }

    public long countPendingAlarms() {
        long count = alarmLogRepository.countByStatus("pending");
        log.debug("待处理告警数量: {}", count);
        return count;
    }

    public long countTodayAlarms() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime todayEnd = LocalDate.now().atTime(LocalTime.MAX);
        long count = alarmLogRepository.countByCreatedAtBetween(todayStart, todayEnd);
        log.debug("今日告警数量: {}", count);
        return count;
    }
}
