package com.streetlight.controller;

import com.streetlight.common.Result;
import com.streetlight.dto.ChatRequest;
import com.streetlight.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public Result<Map<String, String>> chat(@Valid @RequestBody ChatRequest request) {
        String answer = chatService.answer(request.getQuestion());
        return Result.success(Map.of("answer", answer));
    }
}
