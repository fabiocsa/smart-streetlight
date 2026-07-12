package com.streetlight.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分块工具。
 *
 * 按字符数切分文本，块间有重叠以保持语义连贯。
 * 尽量在句号、换行等自然边界处断开，避免截断句子。
 *
 * TODO: 可替换为 LangChain4j 的 DocumentSplitter 或 Spring AI 的 TokenTextSplitter
 *       以获得更精确的 token 级别切分（按 token 计数而非字符数）。
 */
public final class TextSplitter {

    private TextSplitter() {}

    /**
     * @param text     原始文本
     * @param chunkSize    每块最大字符数（默认 500）
     * @param overlap      相邻块重叠字符数（默认 50）
     * @return 文本块列表
     */
    public static List<String> split(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) return List.of();

        List<String> chunks = new ArrayList<>();
        int start = 0;
        int len = text.length();

        while (start < len) {
            int end = Math.min(start + chunkSize, len);

            // 非末尾块：在预期边界附近找自然断点
            if (end < len) {
                int boundary = findBreakPoint(text, end, overlap / 2);
                if (boundary > start) {
                    end = boundary;
                }
            }

            String chunk = text.substring(start, end).strip();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            int newStart = end - overlap;
            // 防止死循环：新起点必须严格大于当前起点，否则强制跳到 end
            if (newStart <= start) {
                start = end;
            } else {
                start = newStart;
            }
            if (start >= len) break;
        }

        return chunks;
    }

    /** 在 target 附近寻找自然语言断点 */
    private static int findBreakPoint(String text, int target, int lookRange) {
        int searchEnd = Math.min(target + lookRange, text.length());
        int searchStart = Math.max(target - lookRange, 0);

        char[] priorityBreaks = {'。', '？', '！', '\n', '；', '，', '.', '?', '!'};

        // 向后找
        for (int i = target; i < searchEnd; i++) {
            if (isBreakChar(text.charAt(i))) return i + 1;
        }
        // 向前找
        for (int i = target - 1; i >= searchStart; i--) {
            if (isBreakChar(text.charAt(i))) return i + 1;
        }

        return target;
    }

    private static boolean isBreakChar(char c) {
        return c == '。' || c == '？' || c == '！' || c == '\n'
                || c == '；' || c == '，' || c == '.' || c == '?' || c == '!';
    }
}
