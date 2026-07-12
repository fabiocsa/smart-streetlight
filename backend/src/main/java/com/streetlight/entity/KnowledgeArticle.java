package com.streetlight.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 知识库文章实体 —— 对应 MySQL 表 knowledge_articles。
 * 如果你的实际表名/字段名不同，请修改 @Table(name = "...") 和 @Column(name = "...") 即可。
 */
@Entity
@Table(name = "knowledge_articles")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 知识标题 */
    @Column(nullable = false, length = 500)
    private String title;

    /** 知识正文（用于语义检索的文本源） */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 知识分类，如"告警处理"、"设备管理"、"传感器"等 */
    @Column(length = 100)
    private String category;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
