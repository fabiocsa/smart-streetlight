package com.streetlight.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 嵌入模型配置 —— 通过环境变量或 application.yml 注入。
 *
 * 推荐嵌入服务及配置示例：
 *
 * 1. 阿里云百炼 text-embedding-v2（OpenAI 兼容）:
 *    EMBEDDING_API_BASE=https://dashscope.aliyuncs.com/compatible-mode/v1
 *    EMBEDDING_API_KEY=sk-xxxxxxxx
 *    EMBEDDING_MODEL=text-embedding-v2
 *
 * 2. 智谱 embedding-2（OpenAI 兼容）:
 *    EMBEDDING_API_BASE=https://open.bigmodel.cn/api/paas/v4
 *    EMBEDDING_API_KEY=xxxxxxxx
 *    EMBEDDING_MODEL=embedding-2
 *
 * 3. 任何 OpenAI 兼容的嵌入服务:
 *    EMBEDDING_API_BASE=https://your-service/v1
 *    EMBEDDING_API_KEY=your-key
 *    EMBEDDING_MODEL=your-model
 */
@Data
@Component
@ConfigurationProperties(prefix = "embedding")
public class EmbeddingConfig {

    /** 是否启用 RAG 知识库（false 时 /api/rag/ask 返回提示） */
    private boolean enabled = true;

    /** 嵌入服务 base URL（OpenAI 兼容接口），末尾不带 / */
    private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";

    /** 嵌入服务 API Key */
    private String apiKey = "";

    /** 嵌入模型名称 */
    private String model = "embedding-2";

    /** 向量维度（embedding-2: 1024, text-embedding-v2: 1536） */
    private int dimension = 1024;
}
