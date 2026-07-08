package com.streetlight.repository;

import com.streetlight.entity.ControlLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ControlLogRepository extends JpaRepository<ControlLog, Long> {

    ControlLog findByDeviceIdAndCommandAndResultIsNull(String deviceId, String command);

    /** 今天控制操作次数 */
    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    /** 最近N条控制日志 */
    List<ControlLog> findTop20ByOrderByCreatedAtDesc();
}
