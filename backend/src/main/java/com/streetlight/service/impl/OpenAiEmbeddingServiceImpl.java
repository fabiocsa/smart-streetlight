package com.streetlight.service.impl;

import com.streetlight.config.EmbeddingConfig;
import com.streetlight.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * 通过 OpenAI 兼容接口调用嵌入模型。
 * 支持阿里云百炼、智谱、Ollama 等任何兼容 OpenAI /v1/embeddings 接口的服务。
 */
@Slf4j
@Service
public class OpenAiEmbeddingServiceImpl implements EmbeddingService {

    private final RestTemplate restTemplate;
    private final EmbeddingConfig config;

    public OpenAiEmbeddingServiceImpl(RestTemplate restTemplate, EmbeddingConfig config) {
        this.restTemplate = restTemplate;
        this.config = config;
    }

    @Override
    public float[] embed(String text) {
        if (!config.isEnabled() || config.getApiKey() == null || config.getApiKey().isBlank()) {
            log.warn("嵌入服务未启用或 API Key 为空，返回零向量");
            return new float[config.getDimension()];
        }

        String url = config.getBaseUrl() + "/embeddings";

        Map<String, Object> body = Map.of(
                "model", config.getModel(),
                "input", text
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getApiKey());

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(body, headers), Map.class);

            return extractEmbedding(response);
        } catch (Exception e) {
            log.error("调用嵌入服务失败: {}", e.getMessage());
            return new float[config.getDimension()];
        }
    }

    @SuppressWarnings("unchecked")
    private float[] extractEmbedding(ResponseEntity<Map> response) {
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            log.warn("嵌入服务返回非 2xx 状态: {}", response.getStatusCode());
            return new float[config.getDimension()];
        }

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
        if (data == null || data.isEmpty()) {
            log.warn("嵌入服务返回空 data 数组");
            return new float[config.getDimension()];
        }

        List<Object> embedding = (List<Object>) data.get(0).get("embedding");
        if (embedding == null) {
            log.warn("嵌入服务返回空 embedding");
            return new float[config.getDimension()];
        }

        float[] result = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            Object val = embedding.get(i);
            if (val instanceof Double d) {
                result[i] = d.floatValue();
            } else if (val instanceof Float f) {
                result[i] = f;
            } else if (val instanceof Number n) {
                result[i] = n.floatValue();
            }
        }
        return result;
    }
}
