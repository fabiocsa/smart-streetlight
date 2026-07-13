package com.streetlight.repository;

import com.streetlight.entity.KnowledgeChangelog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeChangelogRepository extends JpaRepository<KnowledgeChangelog, Long> {

    /** 按文件ID查询操作日志 */
    List<KnowledgeChangelog> findByFileIdOrderByCreatedAtDesc(Long fileId);

    /** 按操作时间倒序查询所有日志 */
    List<KnowledgeChangelog> findAllByOrderByCreatedAtDesc();
}
