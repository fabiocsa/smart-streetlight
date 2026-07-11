package com.streetlight.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 处理人名单实体，映射已有的 handler_list 表
 */
@Entity
@Table(name = "handler_list")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HandlerList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "handler_name", nullable = false, unique = true, length = 50)
    private String handlerName;

    /** 处理次数（累计被分配次数） */
    @Column(name = "handler_count")
    @Builder.Default
    private Integer handlerCount = 0;

    /** 优先级（数字越小优先级越高） */
    @Column(nullable = false)
    @Builder.Default
    private Integer priority = 0;

    /** 是否被占用：0=空闲, 1=占用 */
    @Column(name = "is_occupied", nullable = false)
    @Builder.Default
    private Integer isOccupied = 0;
}
