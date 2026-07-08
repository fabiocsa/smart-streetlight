package com.streetlight.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SensorFrequencyRequest {

    @NotNull(message = "上报频率不能为空")
    private Integer reportFrequency;
}
