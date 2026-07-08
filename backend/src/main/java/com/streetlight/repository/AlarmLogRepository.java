package com.streetlight.repository;

import com.streetlight.entity.AlarmLog;
import com.streetlight.enums.AlarmSeverity;
import com.streetlight.enums.AlarmStatus;
import com.streetlight.enums.AlarmType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    // ===== Dashboard 查询 =====

    /** 最近N条告警 */
    List<AlarmLog> findTop10ByOrderByCreatedAtDesc();

    /** 按严重级别统计告警数 */
    @Query("SELECT a.severity, COUNT(a) FROM AlarmLog a WHERE a.createdAt >= :since GROUP BY a.severity")
    List<Object[]> countBySeveritySince(@Param("since") LocalDateTime since);

    /** 每日告警数（最近N天） */
    @Query(value = "SELECT DATE(created_at) AS dt, COUNT(*) AS cnt FROM alarm_log " +
           "WHERE created_at >= :since GROUP BY DATE(created_at) ORDER BY dt",
           nativeQuery = true)
    List<Object[]> countByDaySince(@Param("since") LocalDateTime since);

    /** 按设备ID分页查询告警 */
    Page<AlarmLog> findByDeviceIdOrderByCreatedAtDesc(String deviceId, Pageable pageable);

    /** 按严重级别和状态分页查询 */
    Page<AlarmLog> findBySeverityAndStatus(AlarmSeverity severity, AlarmStatus status, Pageable pageable);
}
