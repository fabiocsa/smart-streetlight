package com.streetlight.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MaxKB 智能问答平台配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "maxkb")
public class MaxKBProperties {

    /** 是否启用 MaxKB 远程问答 */
    private boolean enabled = false;

    /** MaxKB 服务地址 */
    private String baseUrl = "http://localhost:8081";

    /** API 密钥 */
    private String apiKey;
}
