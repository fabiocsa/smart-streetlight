package com.streetlight.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "control_log", indexes = {
    @Index(name = "idx_cl_device_cmd_result", columnList = "device_id, command, result, created_at"),
    @Index(name = "idx_cl_device_created",    columnList = "device_id, created_at")
})
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

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
