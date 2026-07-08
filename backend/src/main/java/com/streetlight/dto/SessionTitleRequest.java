package com.streetlight.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SessionTitleRequest {
    @NotBlank(message = "标题不能为空")
    private String title;
}
