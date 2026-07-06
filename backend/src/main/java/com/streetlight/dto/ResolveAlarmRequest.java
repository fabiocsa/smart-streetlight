package com.streetlight.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ResolveAlarmRequest {

    @NotBlank(message = "处理人不能为空")
    private String resolvedBy;
}
