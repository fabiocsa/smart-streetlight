package com.streetlight.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "control_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ControlLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, length = 50)
    private String deviceId;

    @Column(nullable = false, length = 10)
    private String command;

    @Column(nullable = false, length = 20)
    private String source;

    @Column(length = 10)
    private String result;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
