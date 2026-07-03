package com.streetlight.repository;

import com.streetlight.entity.SensorData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SensorDataRepository extends JpaRepository<SensorData, Long> {

    List<SensorData> findByDeviceIdAndReportedAtBetweenOrderByReportedAtAsc(
            String deviceId, LocalDateTime start, LocalDateTime end);

    Optional<SensorData> findTopByDeviceIdOrderByReportedAtDesc(String deviceId);

    @Query("SELECT COUNT(s) FROM SensorData s WHERE s.deviceId = :deviceId " +
           "AND s.reportedAt BETWEEN :start AND :end")
    long countByDeviceIdAndTimeRange(
            @Param("deviceId") String deviceId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    @Query("SELECT AVG(s.lightIntensity) FROM SensorData s WHERE s.deviceId = :deviceId " +
           "AND s.reportedAt BETWEEN :start AND :end")
    Double avgLightIntensity(
            @Param("deviceId") String deviceId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    @Query("SELECT MAX(s.lightIntensity) FROM SensorData s WHERE s.deviceId = :deviceId " +
           "AND s.reportedAt BETWEEN :start AND :end")
    Double maxLightIntensity(
            @Param("deviceId") String deviceId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    @Query("SELECT MIN(s.lightIntensity) FROM SensorData s WHERE s.deviceId = :deviceId " +
           "AND s.reportedAt BETWEEN :start AND :end")
    Double minLightIntensity(
            @Param("deviceId") String deviceId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}
