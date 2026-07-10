package com.streetlight.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streetlight.config.DeepSeekConfig;
import com.streetlight.config.ToolPermissionConfig;
import com.streetlight.entity.ChatMessage;
import com.streetlight.entity.ChatSession;
import com.streetlight.repository.ChatMessageRepository;
import com.streetlight.repository.ChatSessionRepository;
import com.streetlight.service.ChatService;
import com.streetlight.service.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    private static final int MAX_HISTORY = 20;

    private final RestTemplate restTemplate;
    private final DeepSeekConfig config;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;
    private final ChatSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;

    public ChatServiceImpl(RestTemplate restTemplate, DeepSeekConfig config,
                           ToolExecutor toolExecutor, ObjectMapper objectMapper,
                           ChatSessionRepository sessionRepo, ChatMessageRepository messageRepo) {
        this.restTemplate = restTemplate;
        this.config = config;
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
    }

    // ==================== 会话管理 ====================

    @Override
    public List<ChatSession> getSessions(String userId) {
        return sessionRepo.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    @Override
    public ChatSession createSession(String userId, String title) {
        ChatSession session = ChatSession.builder()
                .userId(userId)
                .title(title != null ? title : "新对话")
                .build();
        return sessionRepo.save(session);
    }

    @Override
    @Transactional
    public void deleteSession(Long sessionId) {
        messageRepo.deleteBySessionId(sessionId);
        sessionRepo.deleteById(sessionId);
    }

    @Override
    public ChatSession renameSession(Long sessionId, String title) {
        ChatSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("会话不存在, id=" + sessionId));
        session.setTitle(title);
        return sessionRepo.save(session);
    }

    // ==================== 消息 ====================

    @Override
    public List<ChatMessage> getMessages(Long sessionId) {
        return messageRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    @Override
    @Transactional
    public Map<String, Object> sendMessage(Long sessionId, String question) {
        return sendMessage(sessionId, question, ToolPermissionConfig.ROLE_OPERATOR);
    }

    @Override
    @Transactional
    public Map<String, Object> sendMessage(Long sessionId, String question, String role) {
        ChatSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("会话不存在, id=" + sessionId));

        // 默认最低权限
        String effectiveRole = (role != null && !role.isBlank())
                ? role.trim().toLowerCase() : ToolPermissionConfig.ROLE_OPERATOR;

        log.info("AI 问答请求: role={}, sessionId={}, question={}", effectiveRole, sessionId, question);

        // 1. 保存用户消息
        messageRepo.save(ChatMessage.builder()
                .sessionId(sessionId).role("user").content(question).build());

        // 2. 获取历史消息上下文（最近 N 条）
        List<ChatMessage> history = messageRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<Map<String, String>> context = buildContext(history, MAX_HISTORY);

        // 3. 工具选择 + LLM 调用
        String answer;
        if (!config.isEnabled()) {
            answer = "AI 问答服务未启用，请联系管理员。";
        } else {
            answer = generateAnswer(question, context, effectiveRole);
        }

        // 4. 保存 AI 回复
        ChatMessage saved = messageRepo.save(ChatMessage.builder()
                .sessionId(sessionId).role("assistant").content(answer).build());

        // 5. 自动命名：第一条消息时用 question 作为标题
        if (history.size() == 1) {
            String title = question.length() > 20 ? question.substring(0, 20) : question;
            session.setTitle(title);
            sessionRepo.save(session);
        }

        return Map.of("sessionId", sessionId, "answer", answer, "messageId", saved.getId());
    }

    // ==================== LLM 调用 ====================

    private String generateAnswer(String question, List<Map<String, String>> context, String role) {
        // 第一步：工具选择（传入角色以生成对应权限的工具列表）
        ToolCall toolCall = null;
        try {
            toolCall = selectTool(question, role);
        } catch (Exception e) {
            log.warn("工具选择失败，降级为通用问答", e);
        }

        // 第二步：生成回答（带上下文）
        if (toolCall != null && toolCall.tool != null) {
            return answerWithData(question, context, toolCall, role);
        }
        return answerDirect(question, context, role);
    }

    private ToolCall selectTool(String question, String role) {
        String url = config.getBaseUrl() + "/v1/chat/completions";
        Map<String, Object> body = Map.of(
                "model", config.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", toolExecutor.getToolsPrompt(role)),
                        Map.of("role", "user", "content", question)
                ),
                "temperature", 0.0,
                "max_tokens", 300
        );

        HttpEntity<Map<String, Object>> request = buildRequest(body);
        ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
        String content = extractContent(response);
        String json = extractJson(content);
        log.info("LLM 工具选择 (role={}): {}", role, json);

        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("解析工具选择 JSON 失败: {}", json, e);
            return new ToolCall(null, null);
        }
        String toolName = (String) parsed.get("tool");
        if (toolName == null) return new ToolCall(null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) parsed.get("params");
        return new ToolCall(toolName, params != null ? params : Map.of());
    }

    private String answerWithData(String question, List<Map<String, String>> context,
                                  ToolCall toolCall, String role) {
        log.info("执行工具: {} params={} role={}", toolCall.tool, toolCall.params, role);
        String toolResult = toolExecutor.execute(toolCall.tool, toolCall.params, role);

        String systemPrompt = ToolPermissionConfig.getAnswerSystemPrompt(role);

        String userPrompt = "用户问题：" + question + "\n\n系统查询数据：" + toolResult;

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.addAll(context);
        messages.add(Map.of("role", "user", "content", userPrompt));

        return callLLM(messages);
    }

    private String answerDirect(String question, List<Map<String, String>> context, String role) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content",
                ToolPermissionConfig.getDirectSystemPrompt(role)));
        messages.addAll(context);
        messages.add(Map.of("role", "user", "content", question));

        return callLLM(messages);
    }

    @SuppressWarnings("unchecked")
    private String callLLM(List<Map<String, String>> messages) {
        String url = config.getBaseUrl() + "/v1/chat/completions";
        Map<String, Object> body = Map.of(
                "model", config.getModel(),
                "messages", messages,
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

    // ==================== 工具方法 ====================

    private List<Map<String, String>> buildContext(List<ChatMessage> history, int maxCount) {
        List<ChatMessage> recent = history;
        if (history.size() > maxCount) {
            recent = history.subList(history.size() - maxCount, history.size());
        }
        List<Map<String, String>> context = new ArrayList<>();
        for (int i = 0; i < recent.size() - 1; i++) {
            ChatMessage msg = recent.get(i);
            context.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
        }
        return context;
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
