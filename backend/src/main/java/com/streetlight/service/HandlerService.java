package com.streetlight.service;

import com.streetlight.entity.AlarmLog;
import com.streetlight.entity.HandlerList;
import com.streetlight.enums.AssignmentMode;

import java.util.List;

public interface HandlerService {

    /** CRUD */
    List<HandlerList> listHandlers();

    HandlerList createHandler(String handlerName, Integer priority);

    HandlerList updateHandler(Long id, String handlerName, Integer priority);

    void deleteHandler(Long id);

    /** 自动分配处理人并解决告警 */
    HandlerList autoAssignHandler(AlarmLog alarm);

    /** 手动分配处理人并解决告警 */
    HandlerList manualAssignHandler(Long alarmId, Long handlerId);

    /** 释放处理人（解除占用，不减少处理次数） */
    void releaseHandler(Long handlerId);

    /** 分配模式管理 */
    AssignmentMode getAssignmentMode();

    void setAssignmentMode(AssignmentMode mode);
}
