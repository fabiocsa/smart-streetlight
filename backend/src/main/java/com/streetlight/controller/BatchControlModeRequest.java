package com.streetlight.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class BatchControlModeRequest {
    @NotEmpty(message = "设备ID列表不能为空")
    private List<Long> ids;

    @NotBlank(message = "控制模式不能为空")
    private String controlMode;
}
