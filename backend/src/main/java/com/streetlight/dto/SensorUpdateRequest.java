package com.streetlight.dto;

import lombok.Data;

@Data
public class SensorUpdateRequest {

    private String sensorType;

    private String displayName;

    private String dataTopic;

    private Integer reportFrequency;

    private Boolean enabled;

    private String configJson;
}
