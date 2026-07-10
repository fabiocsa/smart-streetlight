package com.streetlight.repository;

import com.streetlight.entity.ControlLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ControlLogRepository extends JpaRepository<ControlLog, Long> {

    /**
     * 查询指定设备+指令的最新一条未收到响应的控制日志。
     * 按创建时间倒序取第一条，避免多条 pending 时匹配到旧记录。
     */
    ControlLog findTopByDeviceIdAndCommandAndResultIsNullOrderByCreatedAtDesc(String deviceId, String command);

    /** 今天控制操作次数 */
    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    /** 最近N条控制日志 */
    List<ControlLog> findTop20ByOrderByCreatedAtDesc();

    /** 按设备ID分页查询控制日志 */
    Page<ControlLog> findByDeviceIdOrderByCreatedAtDesc(String deviceId, Pageable pageable);
}
