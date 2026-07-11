package com.streetlight.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class BatchThresholdRequest {

    @NotEmpty(message = "设备ID列表不能为空")
    private List<Long> ids;

    @NotNull(message = "开灯光照阈值不能为空")
    private Double thresholdOn;

    @NotNull(message = "关灯光照阈值不能为空")
    private Double thresholdOff;

    @AssertTrue(message = "thresholdOn 必须小于 thresholdOff")
    public boolean isThresholdValid() {
        return thresholdOn == null || thresholdOff == null || thresholdOn < thresholdOff;
    }
}
