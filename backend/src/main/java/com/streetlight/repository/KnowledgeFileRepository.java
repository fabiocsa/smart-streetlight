package com.streetlight.repository;

import com.streetlight.entity.KnowledgeFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface KnowledgeFileRepository extends JpaRepository<KnowledgeFile, Long> {

    /** 按文件名查找，用于上传去重 */
    Optional<KnowledgeFile> findByFileName(String fileName);

    /** 删除指定文件名的记录 */
    void deleteByFileName(String fileName);
}
