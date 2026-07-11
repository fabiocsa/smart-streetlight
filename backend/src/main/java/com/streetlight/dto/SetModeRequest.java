package com.streetlight.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SetModeRequest {

    @NotBlank(message = "模式不能为空")
    private String mode;  // "AUTO" 或 "MANUAL"
}
