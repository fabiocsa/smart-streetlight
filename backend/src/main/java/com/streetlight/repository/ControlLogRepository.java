package com.streetlight.repository;

import com.streetlight.entity.ControlLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ControlLogRepository extends JpaRepository<ControlLog, Long> {

    ControlLog findByDeviceIdAndCommandAndResultIsNull(String deviceId, String command);

    List<ControlLog> findByDeviceIdOrderByCreatedAtDesc(String deviceId);
}
