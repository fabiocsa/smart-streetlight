package com.streetlight.repository;

import com.streetlight.entity.AlarmLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AlarmLogRepository extends JpaRepository<AlarmLog, Long> {

    List<AlarmLog> findByDeviceIdOrderByCreatedAtDesc(String deviceId);

    Page<AlarmLog> findByStatus(String status, Pageable pageable);

    Page<AlarmLog> findByAlarmType(String alarmType, Pageable pageable);

    Page<AlarmLog> findByStatusAndAlarmType(String status, String alarmType, Pageable pageable);

    long countByStatus(String status);

    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    List<AlarmLog> findByDeviceIdAndStatus(String deviceId, String status);
}
