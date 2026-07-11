package com.streetlight.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class SensorStrategyRequest {

    @Pattern(regexp = "single|average", message = "sensorStrategy 必须为 single 或 average")
    private String sensorStrategy;

    /** 当 sensorStrategy = single 时，指定主传感器的 DB ID；为 null 表示使用任意传感器（向后兼容） */
    private Long primarySensorId;
}
