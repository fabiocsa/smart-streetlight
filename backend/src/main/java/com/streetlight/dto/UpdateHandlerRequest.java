package com.streetlight.dto;

import lombok.Data;

@Data
public class UpdateHandlerRequest {

    private String handlerName;

    /** 优先级（数字越小优先级越高） */
    private Integer priority;
}
