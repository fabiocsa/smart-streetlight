package com.streetlight.service;

import com.streetlight.dto.RagResponse;

/**
 * RAG 知识库问答服务。
 * 向量检索 + LLM 生成回答。
 */
public interface RagService {

    /**
     * 基于知识库的问答
     * @param question 用户问题
     * @return 回答 + 知识来源
     */
    RagResponse ask(String question);
}
