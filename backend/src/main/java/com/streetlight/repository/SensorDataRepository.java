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

    // ===== 基础 CRUD =====

    List<SensorData> findByDeviceIdAndReportedAtBetweenOrderByReportedAtAsc(
            String deviceId, LocalDateTime start, LocalDateTime end);

    Optional<SensorData> findTopByDeviceIdOrderByReportedAtDesc(String deviceId);

    List<SensorData> findTop50ByOrderByReportedAtDesc();

    Optional<SensorData> findTopByDeviceIdAndSensorTypeOrderByReportedAtDesc(
            String deviceId, String sensorType);

    List<SensorData> findBySensorTypeAndReportedAtBetweenOrderByReportedAtAsc(
            String sensorType, LocalDateTime start, LocalDateTime end);

    // ===== 通用聚合：指定传感器类型 + 指标名 =====

    @Query("SELECT AVG(CAST(function('json_unquote', function('json_extract', s.dataJson, " +
           "CONCAT('$.', :field))) AS double)) FROM SensorData s " +
           "WHERE s.deviceId = :deviceId AND s.reportedAt BETWEEN :start AND :end")
    Double avgByField(@Param("deviceId") String deviceId,
                      @Param("field") String field,
                      @Param("start") LocalDateTime start,
                      @Param("end") LocalDateTime end);

    @Query("SELECT MAX(CAST(function('json_unquote', function('json_extract', s.dataJson, " +
           "CONCAT('$.', :field))) AS double)) FROM SensorData s " +
           "WHERE s.deviceId = :deviceId AND s.reportedAt BETWEEN :start AND :end")
    Double maxByField(@Param("deviceId") String deviceId,
                      @Param("field") String field,
                      @Param("start") LocalDateTime start,
                      @Param("end") LocalDateTime end);

    @Query("SELECT MIN(CAST(function('json_unquote', function('json_extract', s.dataJson, " +
           "CONCAT('$.', :field))) AS double)) FROM SensorData s " +
           "WHERE s.deviceId = :deviceId AND s.reportedAt BETWEEN :start AND :end")
    Double minByField(@Param("deviceId") String deviceId,
                      @Param("field") String field,
                      @Param("start") LocalDateTime start,
                      @Param("end") LocalDateTime end);

    @Query("SELECT COUNT(s) FROM SensorData s WHERE s.deviceId = :deviceId " +
           "AND s.reportedAt BETWEEN :start AND :end")
    long countByDeviceIdAndTimeRange(
            @Param("deviceId") String deviceId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    // ===== 光照专用聚合（兼容旧调用，转调通用方法） =====

    @Query("SELECT AVG(CAST(function('json_unquote', function('json_extract', s.dataJson, " +
           "'$.lightIntensity')) AS double)) FROM SensorData s WHERE s.deviceId = :deviceId " +
           "AND s.reportedAt BETWEEN :start AND :end")
    Double avgLightIntensity(@Param("deviceId") String deviceId,
                             @Param("start") LocalDateTime start,
                             @Param("end") LocalDateTime end);

    @Query("SELECT MAX(CAST(function('json_unquote', function('json_extract', s.dataJson, " +
           "'$.lightIntensity')) AS double)) FROM SensorData s WHERE s.deviceId = :deviceId " +
           "AND s.reportedAt BETWEEN :start AND :end")
    Double maxLightIntensity(@Param("deviceId") String deviceId,
                             @Param("start") LocalDateTime start,
                             @Param("end") LocalDateTime end);

    @Query("SELECT MIN(CAST(function('json_unquote', function('json_extract', s.dataJson, " +
           "'$.lightIntensity')) AS double)) FROM SensorData s WHERE s.deviceId = :deviceId " +
           "AND s.reportedAt BETWEEN :start AND :end")
    Double minLightIntensity(@Param("deviceId") String deviceId,
                             @Param("start") LocalDateTime start,
                             @Param("end") LocalDateTime end);

    // ===== Dashboard 聚合 =====

    @Query(value = "SELECT sd.* FROM sensor_data sd " +
           "INNER JOIN (SELECT device_id, MAX(reported_at) AS max_time FROM sensor_data GROUP BY device_id) latest " +
           "ON sd.device_id = latest.device_id AND sd.reported_at = latest.max_time",
           nativeQuery = true)
    List<SensorData> findLatestPerDevice();

    @Query("SELECT COUNT(s) FROM SensorData s WHERE s.reportedAt >= :todayStart")
    long countToday(@Param("todayStart") LocalDateTime todayStart);

    @Query("SELECT COALESCE(AVG(CAST(function('json_unquote', function('json_extract', s.dataJson, " +
           "'$.lightIntensity')) AS double)), 0) FROM SensorData s WHERE s.reportedAt >= :todayStart")
    Double avgTodayLight(@Param("todayStart") LocalDateTime todayStart);

    @Query("SELECT HOUR(s.reportedAt) AS hr, AVG(CAST(function('json_unquote', " +
           "function('json_extract', s.dataJson, '$.lightIntensity')) AS double)) AS avgVal " +
           "FROM SensorData s WHERE s.reportedAt >= :since " +
           "GROUP BY HOUR(s.reportedAt) ORDER BY hr")
    List<Object[]> hourlyAvgLightSince(@Param("since") LocalDateTime since);

    @Query("SELECT HOUR(s.reportedAt) AS hr, AVG(CAST(function('json_unquote', " +
           "function('json_extract', s.dataJson, '$.lightIntensity')) AS double)) AS avgVal " +
           "FROM SensorData s WHERE s.deviceId = :deviceId AND s.reportedAt >= :since " +
           "GROUP BY HOUR(s.reportedAt) ORDER BY hr")
    List<Object[]> hourlyAvgLightByDevice(@Param("deviceId") String deviceId,
                                           @Param("since") LocalDateTime since);

    // ===== 通用按小时聚合 =====

    @Query("SELECT HOUR(s.reportedAt) AS hr, AVG(CAST(function('json_unquote', " +
           "function('json_extract', s.dataJson, CONCAT('$.', :field))) AS double)) AS avgVal " +
           "FROM SensorData s WHERE s.sensorType = :sensorType AND s.reportedAt >= :since " +
           "GROUP BY HOUR(s.reportedAt) ORDER BY hr")
    List<Object[]> hourlyAvgBySensorType(@Param("sensorType") String sensorType,
                                          @Param("field") String field,
                                          @Param("since") LocalDateTime since);

    @Query("SELECT HOUR(s.reportedAt) AS hr, AVG(CAST(function('json_unquote', " +
           "function('json_extract', s.dataJson, CONCAT('$.', :field))) AS double)) AS avgVal " +
           "FROM SensorData s WHERE s.deviceId = :deviceId AND s.sensorType = :sensorType " +
           "AND s.reportedAt >= :since GROUP BY HOUR(s.reportedAt) ORDER BY hr")
    List<Object[]> hourlyAvgByDeviceAndSensorType(@Param("deviceId") String deviceId,
                                                   @Param("sensorType") String sensorType,
                                                   @Param("field") String field,
                                                   @Param("since") LocalDateTime since);

    // ===== 通用按天聚合 =====

    @Query(value = "SELECT DATE(reported_at) AS dt, " +
           "AVG(CAST(JSON_UNQUOTE(JSON_EXTRACT(data_json, CONCAT('$.', :field))) AS DOUBLE)) AS avg_val " +
           "FROM sensor_data WHERE sensor_type = :sensorType AND reported_at >= :since " +
           "GROUP BY DATE(reported_at) ORDER BY dt",
           nativeQuery = true)
    List<Object[]> dailyAvgBySensorType(@Param("sensorType") String sensorType,
                                         @Param("field") String field,
                                         @Param("since") LocalDateTime since);

    @Query(value = "SELECT DATE(reported_at) AS dt, " +
           "AVG(CAST(JSON_UNQUOTE(JSON_EXTRACT(data_json, CONCAT('$.', :field))) AS DOUBLE)) AS avg_val " +
           "FROM sensor_data WHERE device_id = :deviceId AND sensor_type = :sensorType " +
           "AND reported_at >= :since GROUP BY DATE(reported_at) ORDER BY dt",
           nativeQuery = true)
    List<Object[]> dailyAvgByDeviceAndSensorType(@Param("deviceId") String deviceId,
                                                  @Param("sensorType") String sensorType,
                                                  @Param("field") String field,
                                                  @Param("since") LocalDateTime since);

    // ===== 光照专用的按天聚合 (保留兼容) =====

    @Query(value = "SELECT DATE(reported_at) AS dt, " +
           "AVG(CAST(JSON_UNQUOTE(JSON_EXTRACT(data_json, '$.lightIntensity')) AS DOUBLE)) AS avg_val " +
           "FROM sensor_data WHERE reported_at >= :since " +
           "GROUP BY DATE(reported_at) ORDER BY dt",
           nativeQuery = true)
    List<Object[]> dailyAvgLightSince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT DATE(reported_at) AS dt, " +
           "AVG(CAST(JSON_UNQUOTE(JSON_EXTRACT(data_json, '$.lightIntensity')) AS DOUBLE)) AS avg_val " +
           "FROM sensor_data WHERE device_id = :deviceId AND reported_at >= :since " +
           "GROUP BY DATE(reported_at) ORDER BY dt",
           nativeQuery = true)
    List<Object[]> dailyAvgLightByDevice(@Param("deviceId") String deviceId,
                                          @Param("since") LocalDateTime since);

    // ===== 计数与时间 =====

    @Query("SELECT COUNT(s) FROM SensorData s WHERE s.reportedAt >= :since")
    long countSince(@Param("since") LocalDateTime since);

    @Query("SELECT MAX(s.reportedAt) FROM SensorData s WHERE s.deviceId = :deviceId")
    Optional<LocalDateTime> maxReportedAtByDevice(@Param("deviceId") String deviceId);

    @Query("SELECT MAX(s.reportedAt) FROM SensorData s")
    Optional<LocalDateTime> maxReportedAt();

    // ===== 按传感器类型筛选最新数据 =====

    @Query(value = "SELECT sd.* FROM sensor_data sd " +
           "INNER JOIN (SELECT device_id, sensor_type, MAX(reported_at) AS max_time " +
           "FROM sensor_data WHERE sensor_type = :sensorType GROUP BY device_id, sensor_type) latest " +
           "ON sd.device_id = latest.device_id AND sd.sensor_type = latest.sensor_type " +
           "AND sd.reported_at = latest.max_time",
           nativeQuery = true)
    List<SensorData> findLatestPerDeviceBySensorType(@Param("sensorType") String sensorType);
}
