package com.streetlight.repository;

import com.streetlight.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByDeviceId(String deviceId);

    List<Device> findByStatus(String status);

    List<Device> findByStatusAndLastHeartbeatBefore(String status, LocalDateTime time);

    /**
     * 通过 ID 查询设备并 JOIN FETCH 已绑定的传感器，避免 N+1 查询。
     */
    @Query("SELECT d FROM Device d LEFT JOIN FETCH d.sensors WHERE d.id = :id")
    Optional<Device> findByIdWithSensors(@Param("id") Long id);
}
