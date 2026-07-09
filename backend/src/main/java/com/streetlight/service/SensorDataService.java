package com.streetlight.service;

import com.streetlight.entity.SensorData;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface SensorDataService {

    /**
     * 保存传感器数据并自动联动（光照阈值判断开关灯）。
     * @param deviceId   设备标识
     * @param sensorId   传感器 ID（可为 null）
     * @param sensorType 传感器类型（light/temperature/humidity/power）
     * @param data       完整传感器读数 Map
     * @param reportedAt 上报时间
     */
    SensorData saveAndAutoControl(String deviceId, Long sensorId, String sensorType,
                                   Map<String, Object> data, LocalDateTime reportedAt);

    /** 获取设备最新一条传感器数据 */
    Optional<SensorData> getLatestByDeviceId(String deviceId);

    /** 获取设备指定类型最新一条 */
    Optional<SensorData> getLatestByDeviceIdAndSensorType(String deviceId, String sensorType);

    /** 获取设备历史数据 */
    List<SensorData> getHistory(String deviceId, LocalDateTime start, LocalDateTime end);

    /** 按传感器类型获取历史 */
    List<SensorData> getHistoryBySensorType(String deviceId, String sensorType,
                                             LocalDateTime start, LocalDateTime end);

    /**
     * 聚合统计：指定设备 + 指标名（如 lightIntensity, temperature）。
     * @param deviceId 设备标识
     * @param field    指标字段名（data_json 中的 key）
     * @param start    起始时间
     * @param end      结束时间
     */
    Map<String, Object> getStats(String deviceId, String field,
                                  LocalDateTime start, LocalDateTime end);
}
