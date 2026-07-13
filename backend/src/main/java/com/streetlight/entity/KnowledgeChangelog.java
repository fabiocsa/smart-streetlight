package com.streetlight.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 知识库操作日志 —— 记录文件的上传、更新、删除操作。
 */
@Entity
@Table(name = "knowledge_changelog")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeChangelog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的知识库文件ID */
    @Column(nullable = false)
    private Long fileId;

    /** 文件名（冗余，文件删除后仍可查看历史） */
    @Column(nullable = false, length = 500)
    private String fileName;

    /** 操作类型：UPLOAD / UPDATE / DELETE */
    @Column(nullable = false, length = 20)
    private String action;

    /** 文件类型 */
    @Column(length = 20)
    private String fileType;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 分块数量 */
    private Integer chunkCount;

    /** 操作详情 */
    @Column(length = 1000)
    private String details;

    /** 操作人（后续可对接用户系统） */
    @Column(length = 100)
    private String operator;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
