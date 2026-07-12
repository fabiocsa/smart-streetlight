package com.streetlight.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagResponse {

    /** AI 生成的回答 */
    private String answer;

    /** 检索到的知识来源 */
    private List<SourceInfo> sources;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceInfo {
        /** 来源文件名 */
        private String fileName;
        /** 匹配的文本片段（前 150 字预览） */
        private String snippet;
        /** 相似度得分 */
        private double score;
    }
}
