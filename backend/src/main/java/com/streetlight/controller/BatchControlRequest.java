package com.streetlight.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class BatchControlRequest {
    @NotEmpty(message = "设备ID列表不能为空")
    private List<String> deviceIds;

    @NotBlank(message = "指令不能为空")
    private String command;

    private String source;
}
