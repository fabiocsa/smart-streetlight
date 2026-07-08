package com.streetlight.repository;

import com.streetlight.entity.Sensor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SensorRepository extends JpaRepository<Sensor, Long> {

    List<Sensor> findByDeviceId(String deviceId);

    List<Sensor> findByDeviceIdAndEnabled(String deviceId, Boolean enabled);

    List<Sensor> findByDeviceIdAndSensorType(String deviceId, String sensorType);

    long countByDeviceId(String deviceId);
}
