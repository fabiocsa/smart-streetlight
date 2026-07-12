package com.streetlight.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagRequest {

    /** 用户问题 */
    @NotBlank(message = "问题不能为空")
    private String question;
}
