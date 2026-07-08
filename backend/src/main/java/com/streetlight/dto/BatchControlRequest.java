package com.streetlight.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class BatchControlRequest {

    @NotEmpty(message = "设备列表不能为空")
    private List<String> deviceIds;

    @NotBlank(message = "指令不能为空")
    private String command;

    @Min(value = 0, message = "亮度不能小于0")
    @Max(value = 100, message = "亮度不能大于100")
    private Integer brightness;
}
