package com.streetlight.controller;

import com.streetlight.common.BusinessException;
import com.streetlight.common.Result;
import com.streetlight.dto.CreateHandlerRequest;
import com.streetlight.dto.SetModeRequest;
import com.streetlight.dto.UpdateHandlerRequest;
import com.streetlight.entity.HandlerList;
import com.streetlight.enums.AssignmentMode;
import com.streetlight.service.HandlerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/handlers")
@RequiredArgsConstructor
public class HandlerController {

    private final HandlerService handlerService;

    /** 获取所有处理人列表 */
    @GetMapping
    public Result<List<HandlerList>> listHandlers() {
        return Result.success(handlerService.listHandlers());
    }

    /** 添加处理人 */
    @PostMapping
    public Result<HandlerList> createHandler(@Valid @RequestBody CreateHandlerRequest request) {
        HandlerList handler = handlerService.createHandler(
                request.getHandlerName(), request.getPriority());
        return Result.success(handler);
    }

    /** 更新处理人名称/优先级 */
    @PutMapping("/{id}")
    public Result<HandlerList> updateHandler(
            @PathVariable Long id,
            @RequestBody UpdateHandlerRequest request) {
        HandlerList handler = handlerService.updateHandler(
                id, request.getHandlerName(), request.getPriority());
        return Result.success(handler);
    }

    /** 删除处理人 */
    @DeleteMapping("/{id}")
    public Result<Void> deleteHandler(@PathVariable Long id) {
        handlerService.deleteHandler(id);
        return Result.success();
    }

    /** 释放处理人（解除占用） */
    @PutMapping("/{id}/release")
    public Result<Void> releaseHandler(@PathVariable Long id) {
        handlerService.releaseHandler(id);
        return Result.success();
    }

    /** 获取当前分配模式 */
    @GetMapping("/mode")
    public Result<Map<String, String>> getMode() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("mode", handlerService.getAssignmentMode().name());
        return Result.success(result);
    }

    /** 切换分配模式 */
    @PutMapping("/mode")
    public Result<Void> setMode(@Valid @RequestBody SetModeRequest request) {
        try {
            AssignmentMode mode = AssignmentMode.valueOf(request.getMode().toUpperCase());
            handlerService.setAssignmentMode(mode);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的分配模式: " + request.getMode() + "，请使用 AUTO 或 MANUAL");
        }
        return Result.success();
    }
}
