package com.streetlight.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 告警规则定义。
 * 支持三种规则类型：阈值触发(threshold)、离线检测(offline)、数据中断(data_gap)。
 */
@Entity
@Table(name = "alarm_rule")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlarmRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规则名称 */
    @Column(nullable = false, length = 100)
    private String name;

    /** 规则类型: threshold / offline / data_gap */
    @Column(name = "rule_type", nullable = false, length = 20)
    private String ruleType;

    /** 适用设备ID（null 表示全局规则） */
    @Column(name = "device_id", length = 50)
    private String deviceId;

    /** 传感器类型（阈值规则需要） */
    @Column(name = "sensor_type", length = 30)
    private String sensorType;

    /** 监控指标: light_intensity / power / temperature / voltage */
    @Column(length = 30)
    private String metric;

    /** 比较运算符: gt(大于) / lt(小于) / eq(等于) */
    @Column(length = 5)
    private String operator;

    /** 阈值 */
    @Column(name = "threshold_value")
    private Double thresholdValue;

    /** 持续时间（秒），用于离线检测等需要持续时间的规则 */
    @Column(name = "duration_sec")
    @Builder.Default
    private Integer durationSec = 30;

    /** 是否启用 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /** 触发生成的告警级别 */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String severity = "WARNING";

    /** 规则描述 */
    @Column(length = 500)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
