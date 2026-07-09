package com.streetlight.repository;

import com.streetlight.entity.Sensor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SensorRepository extends JpaRepository<Sensor, Long> {

    /**
     * 查找所有未绑定到任何设备的传感器（sensor.id 不在 device_sensor 中）。
     */
    @Query(value = "SELECT s.* FROM sensor s WHERE s.id NOT IN (SELECT sensor_id FROM device_sensor)", nativeQuery = true)
    List<Sensor> findUnboundSensors();

    /**
     * 查找所有未绑定且处于指定启用状态的传感器。
     */
    @Query(value = "SELECT s.* FROM sensor s WHERE s.id NOT IN (SELECT sensor_id FROM device_sensor) AND s.enabled = :enabled", nativeQuery = true)
    List<Sensor> findUnboundSensorsByEnabled(@Param("enabled") Boolean enabled);
}
