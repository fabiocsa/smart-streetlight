package com.streetlight.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ThresholdRequest {

    @NotNull(message = "开灯光照阈值不能为空")
    private Double thresholdOn;

    @NotNull(message = "关灯光照阈值不能为空")
    private Double thresholdOff;

    @AssertTrue(message = "thresholdOn 必须小于 thresholdOff")
    public boolean isThresholdValid() {
        return thresholdOn == null || thresholdOff == null || thresholdOn < thresholdOff;
    }
}
