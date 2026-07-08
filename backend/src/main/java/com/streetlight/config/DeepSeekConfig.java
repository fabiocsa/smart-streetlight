package com.streetlight.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "deepseek")
public class DeepSeekConfig {
    /** 是否启用 AI 问答，false 时返回提示信息 */
    private boolean enabled = true;
    /** API 地址，默认 DeepSeek 官方 */
    private String baseUrl = "https://api.deepseek.com";
    /** API Key */
    private String apiKey = "sk-bf8442fa885a476d9213c85eb778c1c7";
    /** 模型名称 */
    private String model = "deepseek-chat";
    /** 系统提示词 */
    private String systemPrompt = "你是一个智慧路灯管理系统的智能助手，可以回答关于路灯设备管理、传感器数据、告警处理等方面的问题。请用中文简洁回答。";
    /** 最大输出 token 数 */
    private int maxTokens = 2000;
    /** 温度参数 */
    private double temperature = 0.7;
}
