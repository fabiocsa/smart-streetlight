package com.streetlight.service.impl;

import com.streetlight.config.DeepSeekConfig;
import com.streetlight.config.EmbeddingConfig;
import com.streetlight.dto.RagResponse;
import com.streetlight.service.EmbeddingService;
import com.streetlight.service.RagService;
import com.streetlight.service.VectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

@Slf4j
@Service
public class RagServiceImpl implements RagService {

    private final VectorStore vectorStore;
    private final EmbeddingService embeddingService;
    private final EmbeddingConfig embeddingConfig;
    private final RestTemplate restTemplate;
    private final DeepSeekConfig deepSeekConfig;

    private static final int TOP_K = 3;
    private static final int SNIPPET_MAX_LENGTH = 150;

    public RagServiceImpl(VectorStore vectorStore, EmbeddingService embeddingService,
                          EmbeddingConfig embeddingConfig, RestTemplate restTemplate,
                          DeepSeekConfig deepSeekConfig) {
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
        this.embeddingConfig = embeddingConfig;
        this.restTemplate = restTemplate;
        this.deepSeekConfig = deepSeekConfig;
    }

    @Override
    public RagResponse ask(String question) {
        if (!embeddingConfig.isEnabled() || !deepSeekConfig.isEnabled()) {
            return RagResponse.builder()
                    .answer("RAG 知识库问答服务未启用，请检查 embedding 和 deepseek 配置。")
                    .sources(List.of())
                    .build();
        }

        if (vectorStore.size() == 0) {
            return RagResponse.builder()
                    .answer("知识库为空，请先上传文档（POST /api/knowledge/upload）。")
                    .sources(List.of())
                    .build();
        }

        // 1. 向量检索
        float[] questionEmbedding = embeddingService.embed(question);
        List<VectorStore.SearchResult> results = vectorStore.search(questionEmbedding, TOP_K);

        if (results.isEmpty()) {
            return RagResponse.builder()
                    .answer("未找到相关知识片段，请尝试换个问法，或上传更多相关文档。")
                    .sources(List.of())
                    .build();
        }

        // 2. 拼接知识上下文
        String knowledgeContext = buildKnowledgeContext(results);

        // 3. 构建 RAG system prompt
        String systemPrompt = """
                你是一个智慧路灯管理系统的智能助手，用中文简洁自然地回答用户问题。

                以下是从用户上传的知识文档中检索到的相关内容。
                请基于这些内容来回答问题。如果知识库中的内容不足以回答，请如实告知，不要编造信息。
                回答时请引用来源文件名。

                --- 知识库内容 ---
                %s
                """.formatted(knowledgeContext);

        // 4. 调用 DeepSeek
        String answer = callLLM(systemPrompt, question);

        // 5. 构建来源信息
        List<RagResponse.SourceInfo> sources = results.stream()
                .map(r -> RagResponse.SourceInfo.builder()
                        .fileName(r.fileName())
                        .snippet(truncate(r.content(), SNIPPET_MAX_LENGTH))
                        .score(Math.round(r.score() * 10000.0) / 10000.0)
                        .build())
                .toList();

        return RagResponse.builder()
                .answer(answer)
                .sources(sources)
                .build();
    }

    private String buildKnowledgeContext(List<VectorStore.SearchResult> results) {
        StringJoiner sj = new StringJoiner("\n\n---\n\n");
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            sj.add("【片段 " + (i + 1) + "】（来源：" + r.fileName() + "）\n" + r.content());
        }
        return sj.toString();
    }

    // ==================== LLM 调用 ====================

    @SuppressWarnings("unchecked")
    private String callLLM(String systemPrompt, String question) {
        String url = deepSeekConfig.getBaseUrl() + "/v1/chat/completions";

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", question)
        );

        Map<String, Object> body = Map.of(
                "model", deepSeekConfig.getModel(),
                "messages", messages,
                "temperature", deepSeekConfig.getTemperature(),
                "max_tokens", deepSeekConfig.getMaxTokens()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(deepSeekConfig.getApiKey());

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(body, headers), Map.class);
            return extractContent(response);
        } catch (Exception e) {
            log.error("RAG LLM 调用失败", e);
            return "AI 服务暂时不可用，请稍后重试。";
        }
    }

    @SuppressWarnings("unchecked")
    private String extractContent(ResponseEntity<Map> response) {
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                if (message != null && message.get("content") != null) {
                    return message.get("content").toString().trim();
                }
            }
        }
        return "AI 服务返回格式异常，请稍后重试。";
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
