package com.streetlight.repository;

import com.streetlight.entity.AlarmLog;
import com.streetlight.enums.AlarmStatus;
import com.streetlight.enums.AlarmType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AlarmLogRepository extends JpaRepository<AlarmLog, Long> {

    Page<AlarmLog> findAll(Pageable pageable);

    Page<AlarmLog> findByStatus(AlarmStatus status, Pageable pageable);

    Page<AlarmLog> findByAlarmType(AlarmType type, Pageable pageable);

    Page<AlarmLog> findByStatusAndAlarmType(AlarmStatus status, AlarmType type, Pageable pageable);

    List<AlarmLog> findByDeviceIdAndStatus(String deviceId, AlarmStatus status);

    long countByStatus(AlarmStatus status);

    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
}
