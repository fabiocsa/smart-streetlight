package com.streetlight.entity;

import com.streetlight.enums.AlarmType;
import com.streetlight.enums.AlarmSeverity;
import com.streetlight.enums.AlarmStatus;
import com.streetlight.enums.AssignmentMode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "alarm_log", indexes = {
    @Index(name = "idx_alarm_device_status", columnList = "device_id, status"),
    @Index(name = "idx_alarm_created",       columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlarmLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, length = 50)
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "alarm_type", nullable = false, length = 30)
    private AlarmType alarmType;

    @Column(length = 500)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private AlarmSeverity severity = AlarmSeverity.WARNING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AlarmStatus status = AlarmStatus.PENDING;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by", length = 50)
    private String resolvedBy;

    /** 处理备注 */
    @Column(length = 500)
    private String notes;

    /** 关联的告警规则ID */
    @Column(name = "rule_id")
    private Long ruleId;

    /** 分配的处理人ID（FK → handler_list.id），NULL 表示尚未分配 */
    @Column(name = "assigned_handler_id")
    private Long assignedHandlerId;

    /** 处理人分配模式：AUTO / MANUAL */
    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_mode", length = 10)
    private AssignmentMode assignmentMode;
}
