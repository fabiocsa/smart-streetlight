package com.streetlight.controller;

import com.streetlight.common.Result;
import com.streetlight.dto.ChatRequest;
import com.streetlight.dto.SessionTitleRequest;
import com.streetlight.entity.ChatMessage;
import com.streetlight.entity.ChatSession;
import com.streetlight.service.ChatService;
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
    private static final String DEFAULT_USER = "default_user";

    // ==================== 会话管理 ====================

    @GetMapping("/sessions")
    public Result<List<ChatSession>> listSessions() {
        return Result.success(chatService.getSessions(DEFAULT_USER));
    }

    @PostMapping("/sessions")
    public Result<ChatSession> createSession(@RequestBody(required = false) SessionTitleRequest req) {
        String title = req != null ? req.getTitle() : "新对话";
        return Result.success(chatService.createSession(DEFAULT_USER, title));
    }

    @PutMapping("/sessions/{sessionId}")
    public Result<ChatSession> renameSession(@PathVariable Long sessionId,
                                              @Valid @RequestBody SessionTitleRequest req) {
        return Result.success(chatService.renameSession(sessionId, req.getTitle()));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        chatService.deleteSession(sessionId);
        return Result.success();
    }

    // ==================== 消息 ====================

    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<ChatMessage>> getMessages(@PathVariable Long sessionId) {
        return Result.success(chatService.getMessages(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public Result<Map<String, Object>> sendMessage(@PathVariable Long sessionId,
                                                    @Valid @RequestBody ChatRequest req) {
        return Result.success(chatService.sendMessage(sessionId, req.getQuestion()));
    }
}
