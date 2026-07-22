package com.streetlight.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "device", indexes = {
    @Index(name = "idx_device_status_heartbeat", columnList = "status, last_heartbeat")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "device_id", nullable = false, unique = true, length = 50)
    private String deviceId;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "offline";

    @Column(name = "threshold_on", nullable = false)
    @Builder.Default
    private Double thresholdOn = 50.0;

    @Column(name = "threshold_off", nullable = false)
    @Builder.Default
    private Double thresholdOff = 100.0;

    @Column(name = "light_status", nullable = false, length = 10)
    @Builder.Default
    private String lightStatus = "unknown";

    @Column(name = "control_mode", nullable = false, length = 10)
    @Builder.Default
    private String controlMode = "auto";

    /**
     * 多传感器光照决策策略：single = 以指定传感器为准，average = 所有 light 传感器平均值。
     */
    @Column(name = "sensor_strategy", nullable = false, length = 10)
    @Builder.Default
    private String sensorStrategy = "single";

    /**
     * 当 sensorStrategy = single 时，指定作为光照决策依据的传感器 ID（DB 主键）。
     * 为 null 时使用任意上报的 light 传感器数据（向后兼容）。
     */
    @Column(name = "primary_sensor_id")
    private Long primarySensorId;

    @Column(name = "brightness")
    private Integer brightness;

    @Column(length = 200)
    private String location;

    /**
     * 纬度（WGS84 坐标系）
     */
    @Column(nullable = true)
    private Double latitude;

    /**
     * 经度（WGS84 坐标系）
     */
    @Column(nullable = true)
    private Double longitude;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "device_sensor",
        joinColumns = @JoinColumn(name = "device_id"),
        inverseJoinColumns = @JoinColumn(name = "sensor_id")
    )
    @Builder.Default
    private List<Sensor> sensors = new ArrayList<>();

    /**
     * 已绑定传感器数量（通过 device_sensor 关联表自动计算，非持久化字段）。
     */
    @Formula("(SELECT COUNT(*) FROM device_sensor ds WHERE ds.device_id = id)")
    private int sensorCount;

    @Column(name = "last_heartbeat")
    private LocalDateTime lastHeartbeat;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
