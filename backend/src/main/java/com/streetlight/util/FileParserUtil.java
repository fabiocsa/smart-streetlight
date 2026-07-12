package com.streetlight.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文件文本提取工具。
 *
 * 支持格式：
 *   .txt / .md  → 直接按 UTF-8 读取
 *   .pdf        → Apache PDFBox 提取（自动处理中文）
 *   .docx       → Apache POI XWPFWordExtractor 提取
 *
 * 注意：PDF 复杂排版（多栏、表格、扫描件）可能提取效果不佳，
 *       如需更高精度可考虑接入 OCR 或商业 PDF 解析服务。
 */
@Slf4j
public final class FileParserUtil {

    private FileParserUtil() {}

    /**
     * 根据文件名后缀提取文本内容。
     *
     * @param inputStream 文件输入流（调用方负责关闭）
     * @param filename    原始文件名（用于判断类型）
     * @return 提取出的纯文本
     */
    public static String extract(InputStream inputStream, String filename) throws IOException {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("文件名为空");
        }

        String lower = filename.toLowerCase();

        if (lower.endsWith(".pdf")) {
            return extractPdf(inputStream);
        } else if (lower.endsWith(".docx")) {
            return extractDocx(inputStream);
        } else if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return extractPlainText(inputStream);
        } else {
            // 未知类型按纯文本尝试
            log.warn("未知文件类型: {}，尝试按 UTF-8 纯文本解析", filename);
            return extractPlainText(inputStream);
        }
    }

    // ---- PDF ----

    /**
     * PDFBox 提取文本。
     * PDFBox 3.x 使用 Loader 加载，PDFTextStripper 提取时会自动处理
     * 内嵌中文字体的 CMap，通常不会乱码。若遇乱码，请检查 PDF 是否为扫描件
     * （扫描件需 OCR，PDFBox 无法处理图片型 PDF）。
     */
    private static String extractPdf(InputStream inputStream) throws IOException {
        try (var doc = Loader.loadPDF(inputStream.readAllBytes())) {
            var stripper = new PDFTextStripper();
            // 按段落排序，保留自然阅读顺序
            stripper.setSortByPosition(true);
            // 段落间加空行分隔
            stripper.setParagraphStart("\n");
            stripper.setParagraphEnd("\n");
            String text = stripper.getText(doc);
            return cleanText(text);
        }
    }

    // ---- DOCX ----

    /**
     * POI 提取 DOCX 文本。
     * XWPFWordExtractor 会遍历所有 XWPFParagraph 和表格单元格，
     * 自动过滤掉页眉页脚（通过 CT 标记识别）。
     */
    private static String extractDocx(InputStream inputStream) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(inputStream);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            String text = extractor.getText();
            return cleanText(text);
        }
    }

    // ---- TXT / MD ----

    private static String extractPlainText(InputStream inputStream) throws IOException {
        String text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        return cleanText(text);
    }

    // ---- 公共清理 ----

    /**
     * 清理文本噪音：
     *  - 压缩连续空行为单个空行
     *  - 去除首尾空白
     *  - 去除零宽字符
     *  - 统一换行符为 \n
     *
     * TODO: 可扩展去除页眉页脚、水印等 PDF 特有噪音
     */
    private static String cleanText(String text) {
        if (text == null || text.isBlank()) return "";

        text = text.replace("\r\n", "\n").replace('\r', '\n');
        // 去除零宽字符
        text = text.replaceAll("[\\u200B-\\u200F\\uFEFF]", "");
        // 压缩连续空行为最多 1 个空行
        text = text.replaceAll("\n{3,}", "\n\n");
        // 压缩行内连续空白（但保留换行）
        text = text.lines()
                .map(line -> line.replaceAll("\\s{2,}", " ").stripTrailing())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        return text.strip();
    }
}
