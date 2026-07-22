package com.streetlight.repository;

import com.streetlight.entity.Device;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByDeviceId(String deviceId);

    /**
     * 悲观锁查询：用于自动联动决策时锁定设备行，防止并发重复触发。
     * SELECT ... FOR UPDATE 会阻塞其他事务对同一行的写操作，直到当前事务提交。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Device d WHERE d.deviceId = :deviceId")
    Optional<Device> findByDeviceIdForUpdate(@Param("deviceId") String deviceId);

    List<Device> findByStatus(String status);

    List<Device> findByStatusAndLastHeartbeatBefore(String status, LocalDateTime time);

    long countByStatus(String status);
    long countByLightStatus(String lightStatus);
    long countByControlMode(String controlMode);

    /** 一次性返回 device 表全部统计，避免多次 COUNT 往返远程 DB */
    @Query("SELECT COUNT(d) as total, " +
           "SUM(CASE WHEN d.status = 'online' THEN 1 ELSE 0 END) as online, " +
           "SUM(CASE WHEN d.lightStatus = 'on' THEN 1 ELSE 0 END) as lightsOn, " +
           "SUM(CASE WHEN d.controlMode = 'auto' THEN 1 ELSE 0 END) as autoMode " +
           "FROM Device d")
    List<Object[]> getDeviceStats();

    /**
     * 通过 ID 查询设备并 JOIN FETCH 已绑定的传感器，避免 N+1 查询。
     */
    @Query("SELECT d FROM Device d LEFT JOIN FETCH d.sensors WHERE d.id = :id")
    Optional<Device> findByIdWithSensors(@Param("id") Long id);
}
