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

    // ===== Dashboard 聚合查询 =====

    @Query(value = "SELECT sd.* FROM sensor_data sd " +
           "INNER JOIN (SELECT device_id, MAX(reported_at) AS max_time FROM sensor_data GROUP BY device_id) latest " +
           "ON sd.device_id = latest.device_id AND sd.reported_at = latest.max_time",
           nativeQuery = true)
    List<SensorData> findLatestPerDevice();

    @Query("SELECT COUNT(s) FROM SensorData s WHERE s.reportedAt >= :todayStart")
    long countToday(@Param("todayStart") LocalDateTime todayStart);

    @Query("SELECT COALESCE(AVG(s.lightIntensity), 0) FROM SensorData s WHERE s.reportedAt >= :todayStart")
    Double avgTodayLight(@Param("todayStart") LocalDateTime todayStart);

    @Query("SELECT HOUR(s.reportedAt) AS hr, AVG(s.lightIntensity) AS avgVal " +
           "FROM SensorData s WHERE s.reportedAt >= :since " +
           "GROUP BY HOUR(s.reportedAt) ORDER BY hr")
    List<Object[]> hourlyAvgLightSince(@Param("since") LocalDateTime since);

    @Query("SELECT HOUR(s.reportedAt) AS hr, AVG(s.lightIntensity) AS avgVal " +
           "FROM SensorData s WHERE s.deviceId = :deviceId AND s.reportedAt >= :since " +
           "GROUP BY HOUR(s.reportedAt) ORDER BY hr")
    List<Object[]> hourlyAvgLightByDevice(@Param("deviceId") String deviceId,
                                           @Param("since") LocalDateTime since);

    List<SensorData> findTop50ByOrderByReportedAtDesc();

    @Query(value = "SELECT DATE(reported_at) AS dt, AVG(light_intensity) AS avg_val " +
           "FROM sensor_data WHERE reported_at >= :since " +
           "GROUP BY DATE(reported_at) ORDER BY dt",
           nativeQuery = true)
    List<Object[]> dailyAvgLightSince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT DATE(reported_at) AS dt, AVG(light_intensity) AS avg_val " +
           "FROM sensor_data WHERE device_id = :deviceId AND reported_at >= :since " +
           "GROUP BY DATE(reported_at) ORDER BY dt",
           nativeQuery = true)
    List<Object[]> dailyAvgLightByDevice(@Param("deviceId") String deviceId,
                                          @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(s) FROM SensorData s WHERE s.reportedAt >= :since")
    long countSince(@Param("since") LocalDateTime since);

    @Query("SELECT MAX(s.reportedAt) FROM SensorData s WHERE s.deviceId = :deviceId")
    Optional<LocalDateTime> maxReportedAtByDevice(@Param("deviceId") String deviceId);

    @Query("SELECT MAX(s.reportedAt) FROM SensorData s")
    Optional<LocalDateTime> maxReportedAt();
}
