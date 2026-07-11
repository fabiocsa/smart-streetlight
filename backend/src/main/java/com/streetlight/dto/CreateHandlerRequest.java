package com.streetlight.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateHandlerRequest {

    @NotBlank(message = "处理人名称不能为空")
    private String handlerName;

    /** 优先级（数字越小优先级越高），默认 0 */
    private Integer priority;
}
