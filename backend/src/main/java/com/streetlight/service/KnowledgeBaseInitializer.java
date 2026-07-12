package com.streetlight.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时从 JSON 文件恢复向量存储。
 * 所有知识来源为用户上传的文件（通过 KnowledgeController），不再依赖 MySQL knowledge_articles 表。
 */
@Slf4j
@Component
public class KnowledgeBaseInitializer implements CommandLineRunner {

    private final VectorStore vectorStore;

    public KnowledgeBaseInitializer(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) {
        vectorStore.loadFromFile();
        log.info("向量存储已初始化，当前共 {} 条记录", vectorStore.size());
    }
}
