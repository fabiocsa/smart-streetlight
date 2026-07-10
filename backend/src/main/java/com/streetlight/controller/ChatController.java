package com.streetlight.controller;

import com.streetlight.common.BusinessException;
import com.streetlight.common.Result;
import com.streetlight.dto.ChatRequest;
import com.streetlight.dto.SessionTitleRequest;
import com.streetlight.entity.ChatMessage;
import com.streetlight.entity.ChatSession;
import com.streetlight.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final HttpServletRequest httpRequest;

    /** 从 AuthInterceptor 注入的 request attribute 获取当前登录用户名 */
    private String currentUser() {
        String username = (String) httpRequest.getAttribute("username");
        if (username == null || username.isBlank()) {
            throw new BusinessException("未登录");
        }
        return username;
    }

    /** 从 AuthInterceptor 注入的 request attribute 获取当前用户角色 */
    private String currentRole() {
        String role = (String) httpRequest.getAttribute("role");
        return (role != null && !role.isBlank()) ? role : "municipal";
    }

    /** 验证会话归属：非本人会话拒绝访问 */
    private ChatSession requireOwnSession(Long sessionId) {
        // getSessions 已按用户过滤，此处从用户会话列表中查找
        List<ChatSession> sessions = chatService.getSessions(currentUser());
        return sessions.stream()
                .filter(s -> s.getId().equals(sessionId))
                .findFirst()
                .orElseThrow(() -> new BusinessException("会话不存在或无权访问"));
    }

    // ==================== 会话管理 ====================

    @GetMapping("/sessions")
    public Result<List<ChatSession>> listSessions() {
        return Result.success(chatService.getSessions(currentUser()));
    }

    @PostMapping("/sessions")
    public Result<ChatSession> createSession(@RequestBody(required = false) SessionTitleRequest req) {
        String title = req != null ? req.getTitle() : "新对话";
        return Result.success(chatService.createSession(currentUser(), title));
    }

    @PutMapping("/sessions/{sessionId}")
    public Result<ChatSession> renameSession(@PathVariable Long sessionId,
                                              @Valid @RequestBody SessionTitleRequest req) {
        requireOwnSession(sessionId);
        return Result.success(chatService.renameSession(sessionId, req.getTitle()));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        requireOwnSession(sessionId);
        chatService.deleteSession(sessionId);
        return Result.success();
    }

    // ==================== 消息 ====================

    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<ChatMessage>> getMessages(@PathVariable Long sessionId) {
        requireOwnSession(sessionId);
        return Result.success(chatService.getMessages(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public Result<Map<String, Object>> sendMessage(@PathVariable Long sessionId,
                                                    @Valid @RequestBody ChatRequest req) {
        requireOwnSession(sessionId);
        return Result.success(chatService.sendMessage(sessionId, req.getQuestion(), currentRole()));
    }
}
