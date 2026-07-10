package com.streetlight.service;

import com.streetlight.config.MaxKBProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * MaxKB 智能问答平台客户端
 * <p>
 * 调用 MaxKB 平台的 RAG 知识库 API 获取智能问答回复。
 * 当 MaxKB 不可用或未启用时，返回 null 由 ChatService 走本地降级方案。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MaxKBClient {

    private final MaxKBProperties properties;
    private final RestTemplate restTemplate;

    /**
     * 向 MaxKB 发送问答请求
     *
     * @param question 用户问题
     * @return MaxKB 返回结果，包含 answer、source 等字段；失败返回 null
     */
    public Map<String, Object> ask(String question) {
        if (!properties.isEnabled()) {
            log.debug("MaxKB 未启用，跳过远程问答");
            return null;
        }

        try {
            String url = properties.getBaseUrl() + "/api/v1/application/chat";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(properties.getApiKey());

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("question", question);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.info("MaxKB 问答成功: question={}", question);
                return extractResponse(response.getBody());
            } else {
                log.warn("MaxKB 返回异常: status={}", response.getStatusCode());
                return null;
            }
        } catch (Exception e) {
            log.warn("MaxKB 调用失败，将使用本地问答降级: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 MaxKB 响应中提取标准格式
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractResponse(Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();

        // MaxKB 标准响应格式适配
        String answer = null;
        List<String> sources = new ArrayList<>();

        if (body.containsKey("data")) {
            Map<String, Object> data = (Map<String, Object>) body.get("data");
            answer = (String) data.get("answer");
            if (data.containsKey("source")) {
                Object source = data.get("source");
                if (source instanceof List) {
                    sources = (List<String>) source;
                } else if (source instanceof String) {
                    sources.add((String) source);
                }
            }
        } else if (body.containsKey("answer")) {
            answer = (String) body.get("answer");
        } else if (body.containsKey("content")) {
            answer = (String) body.get("content");
        } else if (body.containsKey("message")) {
            answer = (String) body.get("message");
        }

        if (answer == null || answer.isBlank()) {
            return null;
        }

        result.put("answer", answer);
        result.put("sources", sources);

        if (body.containsKey("suggestions")) {
            result.put("suggestions", body.get("suggestions"));
        }

        return result;
    }
}
