package com.streetlight.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "sensor_data", indexes = {
    @Index(name = "idx_sd_device_reported", columnList = "device_id, reported_at"),
    @Index(name = "idx_sd_type_reported",   columnList = "sensor_type, reported_at"),
    @Index(name = "idx_sd_reported",        columnList = "reported_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Slf4j
public class SensorData {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, length = 50)
    private String deviceId;

    @Column(name = "sensor_id")
    private Long sensorId;

    @Column(name = "sensor_type", nullable = false, length = 30)
    @Builder.Default
    private String sensorType = "light";

    /**
     * JSON 列存储所有传感器读数。
     * 例: {"lightIntensity":125.5, "temperature":28.3, "humidity":65.0, "voltage":226.0, "power":65.0}
     */
    @Column(name = "data_json", nullable = false, columnDefinition = "json")
    private String dataJson;

    @Column(name = "reported_at", nullable = false)
    private LocalDateTime reportedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // ============ JSON ↔ Map 转换 ============

    /**
     * 将 dataJson 反序列化为 Map。
     */
    @Transient
    public Map<String, Object> getData() {
        if (dataJson == null || dataJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return OBJECT_MAPPER.readValue(dataJson, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("传感器数据 JSON 解析失败 (id={}, sensorType={}): {}", id, sensorType, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 将 Map 序列化为 dataJson 字符串。
     */
    public void setData(Map<String, Object> data) {
        try {
            this.dataJson = OBJECT_MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化 sensor data 失败", e);
        }
    }

    // ============ 便利取值方法（安全地从 JSON 中提取常见指标） ============

    @Transient
    public Double getLightIntensity() {
        return getDoubleField("lightIntensity");
    }

    @Transient
    public Double getIlluminance() {
        return getDoubleField("illuminance");
    }

    @Transient
    public Double getTemperature() {
        return getDoubleField("temperature");
    }

    @Transient
    public Double getHumidity() {
        return getDoubleField("humidity");
    }

    @Transient
    public Double getVoltage() {
        return getDoubleField("voltage");
    }

    @Transient
    public Double getPower() {
        return getDoubleField("power");
    }

    @Transient
    public Double getCloudCover() {
        return getDoubleField("cloudCover");
    }

    @Transient
    public String getStatus() {
        return getStringField("status");
    }

    /**
     * 通用 double 提取
     */
    @Transient
    public Double getDoubleField(String fieldName) {
        Map<String, Object> data = getData();
        Object val = data.get(fieldName);
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        return null;
    }

    /**
     * 通用 string 提取
     */
    @Transient
    public String getStringField(String fieldName) {
        Map<String, Object> data = getData();
        Object val = data.get(fieldName);
        return val != null ? val.toString() : null;
    }

    // ============ Builder 辅助 ============

    /**
     * 便捷构造：从 Map 构建 SensorData。
     */
    public static SensorData from(String deviceId, Long sensorId, String sensorType,
                                   Map<String, Object> data, LocalDateTime reportedAt) {
        SensorData sd = new SensorData();
        sd.setDeviceId(deviceId);
        sd.setSensorId(sensorId);
        sd.setSensorType(sensorType);
        sd.setData(data);
        sd.setReportedAt(reportedAt);
        return sd;
    }
}
