package com.streetlight.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ControlRequest {

    @NotBlank(message = "指令不能为空")
    private String command;
}
