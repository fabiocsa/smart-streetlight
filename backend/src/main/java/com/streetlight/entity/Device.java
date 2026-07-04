package com.streetlight.entity;

import com.streetlight.enums.DeviceStatus;
import com.streetlight.enums.LightStatus;
import com.streetlight.enums.ControlMode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "device")
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DeviceStatus status = DeviceStatus.OFFLINE;

    @Column(name = "threshold_on", nullable = false)
    @Builder.Default
    private Double thresholdOn = 50.0;

    @Column(name = "threshold_off", nullable = false)
    @Builder.Default
    private Double thresholdOff = 100.0;

    @Enumerated(EnumType.STRING)
    @Column(name = "light_status", nullable = false, length = 10)
    @Builder.Default
    private LightStatus lightStatus = LightStatus.OFF;

    @Enumerated(EnumType.STRING)
    @Column(name = "control_mode", nullable = false, length = 10)
    @Builder.Default
    private ControlMode controlMode = ControlMode.AUTO;

    @Column(length = 200)
    private String location;

    @Column(name = "last_heartbeat")
    private LocalDateTime lastHeartbeat;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
