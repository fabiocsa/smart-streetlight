package com.streetlight.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
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

    @NotBlank(message = "设备名称不能为空")
    @Column(nullable = false, length = 100)
    private String name;

    @NotBlank(message = "设备标识不能为空")
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

    @Column(length = 200)
    private String location;

    @Column(name = "last_heartbeat")
    private LocalDateTime lastHeartbeat;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
