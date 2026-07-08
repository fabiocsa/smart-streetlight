package com.streetlight.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streetlight.config.DeepSeekConfig;
import com.streetlight.service.ChatService;
import com.streetlight.service.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    private final RestTemplate restTemplate;
    private final DeepSeekConfig config;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;

    public ChatServiceImpl(RestTemplate restTemplate, DeepSeekConfig config,
                           ToolExecutor toolExecutor, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.config = config;
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public String answer(String question) {
        if (!config.isEnabled()) {
            return "AI 问答服务未启用，请联系管理员。";
        }

        // 第一步：意图识别 + 工具选择
        ToolCall toolCall = null;
        try {
            toolCall = selectTool(question);
        } catch (Exception e) {
            log.warn("工具选择失败，降级为通用问答", e);
        }

        // 第二步：根据工具选择执行查询并生成最终回答
        if (toolCall != null && toolCall.tool != null) {
            return answerWithData(question, toolCall);
        }
        return answerDirect(question);
    }

    /**
     * 第一步：将工具列表 + 用户问题发送给 LLM，获取 JSON 格式的工具调用
     */
    private ToolCall selectTool(String question) {
        String url = config.getBaseUrl() + "/v1/chat/completions";

        Map<String, Object> body = Map.of(
                "model", config.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", toolExecutor.getToolsPrompt()),
                        Map.of("role", "user", "content", question)
                ),
                "temperature", 0.0,
                "max_tokens", 300
        );

        HttpEntity<Map<String, Object>> request = buildRequest(body);
        ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
        String content = extractContent(response);

        // 尝试从返回内容中提取 JSON
        String json = extractJson(content);
        log.info("LLM 工具选择: {}", json);

        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("解析工具选择 JSON 失败: {}", json, e);
            return new ToolCall(null, null);
        }
        String toolName = (String) parsed.get("tool");
        if (toolName == null) {
            return new ToolCall(null, null);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) parsed.get("params");
        if (params == null) params = Map.of();
        return new ToolCall(toolName, params);
    }

    /**
     * 第二步：用工具返回的数据 + 用户问题，让 LLM 生成自然语言回答
     */
    private String answerWithData(String question, ToolCall toolCall) {
        log.info("执行工具: {} params={}", toolCall.tool, toolCall.params);
        String toolResult = toolExecutor.execute(toolCall.tool, toolCall.params);

        String systemPrompt = """
                你是智慧路灯管理系统的智能助手，用中文简洁自然地回答用户问题。
                以下是系统查询到的实时数据，请基于这些数据回答用户问题。
                如果数据中有 error 字段，请友好地告知用户查询出了问题。
                不要编造数据中没有的信息。""";

        String userPrompt = "用户问题：" + question + "\n\n系统查询数据：" + toolResult;

        String url = config.getBaseUrl() + "/v1/chat/completions";
        Map<String, Object> body = Map.of(
                "model", config.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "temperature", config.getTemperature(),
                "max_tokens", config.getMaxTokens()
        );

        try {
            HttpEntity<Map<String, Object>> request = buildRequest(body);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            return extractContent(response);
        } catch (Exception e) {
            log.error("LLM 生成回答失败", e);
            // 降级：直接返回数据结构给用户
            return "查询到以下数据（AI 生成回答失败）：\n" + toolResult;
        }
    }

    /**
     * 直接问答（不涉及内部数据）
     */
    private String answerDirect(String question) {
        String url = config.getBaseUrl() + "/v1/chat/completions";
        Map<String, Object> body = Map.of(
                "model", config.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", config.getSystemPrompt()),
                        Map.of("role", "user", "content", question)
                ),
                "temperature", config.getTemperature(),
                "max_tokens", config.getMaxTokens()
        );

        try {
            HttpEntity<Map<String, Object>> request = buildRequest(body);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            return extractContent(response);
        } catch (Exception e) {
            log.error("LLM 调用失败", e);
            return "AI 服务暂时不可用，请稍后重试";
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
        log.warn("LLM 返回格式异常: {}", response.getBody());
        return "AI 服务返回格式异常，请稍后重试";
    }

    private String extractJson(String content) {
        if (content == null) return "{\"tool\": null}";
        content = content.trim();
        // 去掉 markdown 代码块包裹
        if (content.startsWith("```")) {
            int start = content.indexOf("\n");
            int end = content.lastIndexOf("```");
            if (start >= 0 && end > start) {
                content = content.substring(start, end).trim();
            }
        }
        int braceStart = content.indexOf('{');
        int braceEnd = content.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return content.substring(braceStart, braceEnd + 1);
        }
        return "{\"tool\": null}";
    }

    private HttpEntity<Map<String, Object>> buildRequest(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getApiKey());
        return new HttpEntity<>(body, headers);
    }

    private record ToolCall(String tool, Map<String, Object> params) {}
}
