package com.streetlight.mqtt;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MQTT 传感器数据 JSON 解析用 DTO
 * <p>
 * 对应 MQTT 消息 JSON 格式：
 * <pre>
 * {"deviceId":"SL-001","lightIntensity":125.5,"reportedAt":"2026-07-04T10:00:00"}
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LightData {

    /** 设备编号 */
    private String deviceId;

    /** 光照强度 (lux) */
    private Double lightIntensity;

    /** 数据上报时间（ISO 格式字符串） */
    private String reportedAt;
}
