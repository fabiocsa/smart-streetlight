package com.streetlight.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ControlModeRequest {

    @NotBlank(message = "控制模式不能为空")
    private String controlMode;
}
