package com.streetlight.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 传感器定义实体
 * 每个设备可绑定多个传感器，每个传感器有自己的类型、主题、上报频率等属性。
 */
@Entity
@Table(name = "sensor")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Sensor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", length = 50)
    private String deviceId;

    @Column(name = "sensor_type", nullable = false, length = 30)
    @Builder.Default
    private String sensorType = "light";

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "data_topic", nullable = false, length = 200)
    private String dataTopic;

    @Column(name = "report_frequency", nullable = false)
    @Builder.Default
    private Integer reportFrequency = 5;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "config_json", length = 500)
    private String configJson;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
