package com.streetlight.repository;

import com.streetlight.entity.Device;
import com.streetlight.enums.DeviceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByDeviceId(String deviceId);

    List<Device> findByStatus(DeviceStatus status);

    List<Device> findByStatusAndLastHeartbeatBefore(DeviceStatus status, LocalDateTime time);
}
