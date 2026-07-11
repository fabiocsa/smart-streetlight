package com.streetlight.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 系统配置表（KV 键值对）
 */
@Entity
@Table(name = "system_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false, unique = true, length = 50)
    private String configKey;

    @Column(name = "config_value", length = 100)
    private String configValue;
}
