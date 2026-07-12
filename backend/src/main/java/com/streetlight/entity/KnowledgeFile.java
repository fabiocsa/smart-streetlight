package com.streetlight.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 知识库文件 —— 记录用户上传的文件元信息。
 * 向量数据本身存储在 VectorStore（JSON 持久化），此表仅用于文件管理和去重。
 */
@Entity
@Table(name = "knowledge_files",
       uniqueConstraints = @UniqueConstraint(columnNames = "fileName"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 原始文件名（含扩展名），用于去重：重复上传同名文件会先清除旧数据 */
    @Column(nullable = false, length = 500)
    private String fileName;

    /** 文件类型：txt / md / pdf / docx */
    @Column(nullable = false, length = 20)
    private String fileType;

    /** 原始文件大小（字节） */
    private long fileSize;

    /** 文本分块数量 */
    private int chunkCount;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime uploadedAt;
}
