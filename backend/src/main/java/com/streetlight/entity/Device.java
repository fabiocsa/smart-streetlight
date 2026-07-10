package com.streetlight.entity;

import com.streetlight.enums.DeviceStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
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
    private String lightStatus = "off";

    @Column(name = "control_mode", nullable = false, length = 10)
    @Builder.Default
    private String controlMode = "auto";

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

    @Column(name = "last_heartbeat")
    private LocalDateTime lastHeartbeat;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
