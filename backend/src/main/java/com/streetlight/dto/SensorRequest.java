package com.streetlight.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SensorRequest {

    @NotBlank(message = "传感器类型不能为空")
    private String sensorType;

    private String displayName;

    @NotBlank(message = "数据主题不能为空")
    private String dataTopic;

    @NotNull(message = "上报频率不能为空")
    private Integer reportFrequency;

    private String configJson;
}
