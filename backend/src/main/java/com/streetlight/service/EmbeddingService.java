package com.streetlight.service;

/**
 * 文本向量化服务接口。
 * 将文本转为向量（float 数组），用于语义相似度检索。
 */
public interface EmbeddingService {

    /**
     * 将文本转为向量
     * @param text 输入文本
     * @return 向量（float 数组，长度 = embedding.dimension）
     */
    float[] embed(String text);
}
