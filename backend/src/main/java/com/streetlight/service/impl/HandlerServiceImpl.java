package com.streetlight.service.impl;

import com.streetlight.common.BusinessException;
import com.streetlight.entity.AlarmLog;
import com.streetlight.entity.HandlerList;
import com.streetlight.entity.SystemConfig;
import com.streetlight.enums.AlarmStatus;
import com.streetlight.enums.AssignmentMode;
import com.streetlight.repository.AlarmLogRepository;
import com.streetlight.repository.HandlerListRepository;
import com.streetlight.repository.SystemConfigRepository;
import com.streetlight.service.HandlerService;
import com.streetlight.websocket.WebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class HandlerServiceImpl implements HandlerService {

    private final HandlerListRepository handlerListRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final AlarmLogRepository alarmLogRepository;
    private final WebSocketHandler webSocketHandler;

    // ---------- CRUD ----------

    @Override
    public List<HandlerList> listHandlers() {
        return handlerListRepository.findAll();
    }

    @Override
    @Transactional
    public HandlerList createHandler(String handlerName, Integer priority) {
        if (handlerListRepository.findByHandlerName(handlerName).isPresent()) {
            throw new BusinessException("处理人 " + handlerName + " 已存在");
        }
        HandlerList handler = HandlerList.builder()
                .handlerName(handlerName)
                .priority(priority != null ? priority : 0)
                .handlerCount(0)
                .isOccupied(0)
                .build();
        HandlerList saved = handlerListRepository.save(handler);
        log.info("添加处理人: name={}, priority={}", handlerName, saved.getPriority());
        return saved;
    }

    @Override
    @Transactional
    public HandlerList updateHandler(Long id, String handlerName, Integer priority) {
        HandlerList handler = handlerListRepository.findById(id)
                .orElseThrow(() -> new BusinessException("处理人不存在, id=" + id));
        if (handlerName != null && !handlerName.isBlank()) {
            // 检查名称唯一性
            handlerListRepository.findByHandlerName(handlerName).ifPresent(h -> {
                if (!h.getId().equals(id)) {
                    throw new BusinessException("处理人 " + handlerName + " 已存在");
                }
            });
            handler.setHandlerName(handlerName);
        }
        if (priority != null) {
            handler.setPriority(priority);
        }
        HandlerList updated = handlerListRepository.save(handler);
        log.info("更新处理人: id={}, name={}, priority={}", id, handler.getHandlerName(), handler.getPriority());
        return updated;
    }

    @Override
    @Transactional
    public void deleteHandler(Long id) {
        HandlerList handler = handlerListRepository.findById(id)
                .orElseThrow(() -> new BusinessException("处理人不存在, id=" + id));
        handlerListRepository.delete(handler);
        log.info("删除处理人: id={}, name={}", id, handler.getHandlerName());
    }

    // ---------- 分配 ----------

    @Override
    @Transactional
    public HandlerList autoAssignHandler(AlarmLog alarm) {
        List<HandlerList> allHandlers = handlerListRepository.findAll();
        if (allHandlers.isEmpty()) {
            log.warn("自动分配失败: 没有可用的处理人, alarmId={}", alarm.getId());
            throw new BusinessException("没有可用的处理人，请先添加处理人或切换为手动模式");
        }

        // 1. 优先选择空闲处理人（优先级数字越小优先级越高）
        List<HandlerList> freeHandlers = handlerListRepository.findByIsOccupiedOrderByPriorityAsc(0);

        HandlerList selected;
        if (!freeHandlers.isEmpty()) {
            selected = freeHandlers.get(0);
        } else {
            // 2. 全部占用 → 按处理次数升序、优先级升序选择
            List<HandlerList> sorted = handlerListRepository.findAllByOrderByHandlerCountAscPriorityAsc();
            selected = sorted.get(0);
        }

        // 3. 标记处理人：占用 + 次数 +1
        selected.setIsOccupied(1);
        selected.setHandlerCount(selected.getHandlerCount() + 1);
        handlerListRepository.save(selected);

        // 4. 解决告警
        alarm.setStatus(AlarmStatus.RESOLVED);
        alarm.setResolvedAt(LocalDateTime.now());
        alarm.setResolvedBy(selected.getHandlerName());
        alarm.setAssignedHandlerId(selected.getId());
        alarm.setAssignmentMode(AssignmentMode.AUTO);
        alarmLogRepository.save(alarm);

        log.info("自动分配处理人: alarmId={}, deviceId={}, handler={}",
                alarm.getId(), alarm.getDeviceId(), selected.getHandlerName());

        // 推送 WebSocket
        webSocketHandler.pushNewAlarm(
                alarm.getId(),
                alarm.getDeviceId(),
                "resolved",
                "告警已自动分配处理人 " + selected.getHandlerName(),
                "info"
        );

        return selected;
    }

    @Override
    @Transactional
    public HandlerList manualAssignHandler(Long alarmId, Long handlerId) {
        AlarmLog alarm = alarmLogRepository.findById(alarmId)
                .orElseThrow(() -> new BusinessException("告警不存在, id=" + alarmId));

        HandlerList handler = handlerListRepository.findById(handlerId)
                .orElseThrow(() -> new BusinessException("处理人不存在, id=" + handlerId));

        // 如果告警已解决且之前有别的处理人，先释放旧的处理人
        if (alarm.getStatus() == AlarmStatus.RESOLVED && alarm.getAssignedHandlerId() != null
                && !alarm.getAssignedHandlerId().equals(handlerId)) {
            handlerListRepository.findById(alarm.getAssignedHandlerId()).ifPresent(oldHandler -> {
                oldHandler.setIsOccupied(0);
                handlerListRepository.save(oldHandler);
            });
        }

        // 标记处理人
        handler.setIsOccupied(1);
        handler.setHandlerCount(handler.getHandlerCount() + 1);
        handlerListRepository.save(handler);

        // 解决告警
        alarm.setStatus(AlarmStatus.RESOLVED);
        alarm.setResolvedAt(LocalDateTime.now());
        alarm.setResolvedBy(handler.getHandlerName());
        alarm.setAssignedHandlerId(handler.getId());
        alarm.setAssignmentMode(AssignmentMode.MANUAL);
        alarmLogRepository.save(alarm);

        log.info("手动分配处理人: alarmId={}, handler={}", alarmId, handler.getHandlerName());
        return handler;
    }

    // ---------- 释放 ----------

    @Override
    @Transactional
    public void releaseHandler(Long handlerId) {
        HandlerList handler = handlerListRepository.findById(handlerId)
                .orElse(null);
        if (handler == null) {
            log.warn("释放处理人跳过: handlerId={} 不存在（可能已被删除）", handlerId);
            return;
        }
        handler.setIsOccupied(0);
        handlerListRepository.save(handler);
        log.info("释放处理人: id={}, name={}", handlerId, handler.getHandlerName());
    }

    // ---------- 模式管理 ----------

    @Override
    public AssignmentMode getAssignmentMode() {
        SystemConfig config = systemConfigRepository.findByConfigKey("assignment_mode")
                .orElse(null);
        if (config == null) {
            return AssignmentMode.MANUAL; // 默认手动模式
        }
        try {
            return AssignmentMode.valueOf(config.getConfigValue().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("无效的分配模式配置: {}, 回退为手动模式", config.getConfigValue());
            return AssignmentMode.MANUAL;
        }
    }

    @Override
    @Transactional
    public void setAssignmentMode(AssignmentMode mode) {
        SystemConfig config = systemConfigRepository.findByConfigKey("assignment_mode")
                .orElse(SystemConfig.builder()
                        .configKey("assignment_mode")
                        .configValue("MANUAL")
                        .build());
        config.setConfigValue(mode.name());
        systemConfigRepository.save(config);
        log.info("切换分配模式: {}", mode.name());
    }
}