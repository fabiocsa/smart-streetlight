package com.streetlight.controller;

import com.streetlight.common.Result;
import com.streetlight.dto.RagRequest;
import com.streetlight.dto.RagResponse;
import com.streetlight.service.RagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * RAG 知识库问答接口。
 *
 * 测试方式：
 *   curl -X POST http://localhost:8080/api/rag/ask \
 *     -H "Content-Type: application/json" \
 *     -d '{"question": "路灯不亮怎么处理？"}'
 */
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;

    @PostMapping("/ask")
    public Result<RagResponse> ask(@Valid @RequestBody RagRequest req) {
        RagResponse response = ragService.ask(req.getQuestion());
        return Result.success(response);
    }
}
