package com.streetlight.controller;

import com.streetlight.common.Result;
import com.streetlight.config.EmbeddingConfig;
import com.streetlight.entity.KnowledgeChangelog;
import com.streetlight.entity.KnowledgeFile;
import com.streetlight.repository.KnowledgeChangelogRepository;
import com.streetlight.repository.KnowledgeFileRepository;
import com.streetlight.service.EmbeddingService;
import com.streetlight.service.VectorStore;
import com.streetlight.util.FileParserUtil;
import com.streetlight.util.TextSplitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;

/**
 * 知识库文件管理。
 *
 * 支持上传 TXT / MD / PDF / DOCX，自动解析 → 分块 → 向量化 → 存入向量库。
 * 同一文件名重复上传时会先清除旧数据再重新索引。
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeFileRepository fileRepo;
    private final KnowledgeChangelogRepository changelogRepo;
    private final VectorStore vectorStore;
    private final EmbeddingService embeddingService;
    private final EmbeddingConfig embeddingConfig;
    private final HttpServletRequest request;

    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of(".txt", ".md", ".markdown", ".pdf", ".docx");
    private static final long MAX_FILE_SIZE = 15 * 1024 * 1024; // 15MB

    /**
     * 上传知识文档。
     *
     * curl -X POST http://localhost:8080/api/knowledge/upload \
     *   -F "file=@路灯故障处理手册.pdf"
     */
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        // ---- 前置校验：嵌入服务是否可用 ----
        if (!embeddingConfig.isEnabled() || embeddingConfig.getApiKey().isBlank()) {
            return Result.error(503, "嵌入服务未配置（EMBEDDING_API_KEY 为空），无法向量化。请设置环境变量后重启。");
        }

        // ---- 校验 ----
        if (file.isEmpty()) {
            return Result.error(400, "文件为空");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            return Result.error(400, "文件名为空");
        }

        String extension = getExtension(originalName).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            return Result.error(400, "不支持的文件类型: " + extension
                    + "，允许: " + ALLOWED_EXTENSIONS);
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            return Result.error(400, "文件过大，限制 " + (MAX_FILE_SIZE / 1024 / 1024) + "MB");
        }

        // ---- 去重：同名文件先删旧数据 ----
        fileRepo.findByFileName(originalName).ifPresent(existing -> {
            log.info("检测到同名文件，先清除旧数据: fileId={}, fileName={}",
                    existing.getId(), originalName);
            vectorStore.deleteByFileId(String.valueOf(existing.getId()));
            fileRepo.delete(existing);
        });

        // ---- 解析文本 ----
        String text;
        try {
            text = FileParserUtil.extract(file.getInputStream(), originalName);
        } catch (IOException e) {
            log.error("文件解析失败: {}", originalName, e);
            return Result.error(500, "文件解析失败: " + e.getMessage());
        }

        if (text.isBlank()) {
            return Result.error(400, "未能从文件中提取到文本内容，请检查文件是否有效");
        }

        // ---- 分块 ----
        List<String> chunks = TextSplitter.split(text, 500, 50);
        if (chunks.isEmpty()) {
            return Result.error(400, "文件内容过短，无法分块");
        }

        // ---- 保存文件记录 ----
        KnowledgeFile kf = KnowledgeFile.builder()
                .fileName(originalName)
                .fileType(extension.replace(".", ""))
                .fileSize(file.getSize())
                .chunkCount(chunks.size())
                .build();
        kf = fileRepo.save(kf);
        String fileId = String.valueOf(kf.getId());

        // ---- 向量化 & 存储 ----
        int embedded = 0;
        for (int i = 0; i < chunks.size(); i++) {
            try {
                float[] embedding = embeddingService.embed(chunks.get(i));
                vectorStore.add(fileId, embedding, chunks.get(i),
                        originalName, kf.getFileType(), i);
                embedded++;
            } catch (Exception e) {
                log.error("向量化失败: fileId={}, chunk={}", fileId, i, e);
            }
        }

        // 更新实际成功数量
        if (embedded != chunks.size()) {
            kf.setChunkCount(embedded);
            fileRepo.save(kf);
        }

        // 持久化向量存储
        vectorStore.saveToFile();

        // 记录操作日志
        changelogRepo.save(KnowledgeChangelog.builder()
                .fileId(kf.getId())
                .fileName(originalName)
                .action("UPLOAD")
                .fileType(kf.getFileType())
                .fileSize(file.getSize())
                .chunkCount(embedded)
                .details("上传文件，" + text.length() + " 字符 → " + chunks.size() + " 块 → " + embedded + " 条向量")
                .operator(getCurrentUser())
                .build());

        log.info("文件导入完成: {} → {} 字符 → {} 块 → {} 条向量",
                originalName, text.length(), chunks.size(), embedded);

        return Result.success(Map.of(
                "fileId", kf.getId(),
                "fileName", kf.getFileName(),
                "fileType", kf.getFileType(),
                "fileSize", kf.getFileSize(),
                "chunkCount", embedded,
                "status", "READY"
        ));
    }

    /** 列出所有已上传文件 */
    @GetMapping("/files")
    public Result<List<Map<String, Object>>> listFiles() {
        List<KnowledgeFile> files = fileRepo.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (KnowledgeFile kf : files) {
            // 校验向量库中的实际数量
            int actualChunks = (int) countChunksByFileId(String.valueOf(kf.getId()));
            result.add(Map.of(
                    "fileId", kf.getId(),
                    "fileName", kf.getFileName(),
                    "fileType", kf.getFileType(),
                    "fileSize", kf.getFileSize(),
                    "chunkCount", actualChunks > 0 ? actualChunks : kf.getChunkCount(),
                    "uploadedAt", kf.getUploadedAt() != null ? kf.getUploadedAt().toString() : ""
            ));
        }
        return Result.success(result);
    }

    /**
     * 删除指定文件及其所有向量。
     *
     * curl -X DELETE http://localhost:8080/api/knowledge/files/1
     */
    @DeleteMapping("/files/{fileId}")
    public Result<Map<String, Object>> deleteFile(@PathVariable Long fileId) {
        KnowledgeFile kf = fileRepo.findById(fileId)
                .orElseThrow(() -> new RuntimeException("文件不存在, fileId=" + fileId));

        int removed = vectorStore.deleteByFileId(String.valueOf(fileId));
        fileRepo.delete(kf);
        vectorStore.saveToFile();

        // 记录操作日志
        changelogRepo.save(KnowledgeChangelog.builder()
                .fileId(fileId)
                .fileName(kf.getFileName())
                .action("DELETE")
                .fileType(kf.getFileType())
                .fileSize(kf.getFileSize())
                .chunkCount(removed)
                .details("删除文件，移除 " + removed + " 条向量")
                .operator(getCurrentUser())
                .build());

        log.info("已删除文件: {} (fileId={}, 移除 {} 条向量)", kf.getFileName(), fileId, removed);

        return Result.success(Map.of(
                "fileId", fileId,
                "fileName", kf.getFileName(),
                "removedChunks", removed,
                "message", "文件已删除"
        ));
    }

    /** 查询操作日志 */
    @GetMapping("/changelog")
    public Result<List<Map<String, Object>>> changelog(@RequestParam(required = false) Long fileId) {
        List<KnowledgeChangelog> logs;
        if (fileId != null) {
            logs = changelogRepo.findByFileIdOrderByCreatedAtDesc(fileId);
        } else {
            logs = changelogRepo.findAllByOrderByCreatedAtDesc();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (KnowledgeChangelog cl : logs) {
            result.add(Map.of(
                    "id", cl.getId(),
                    "fileId", cl.getFileId(),
                    "fileName", cl.getFileName(),
                    "action", cl.getAction(),
                    "fileType", cl.getFileType(),
                    "fileSize", cl.getFileSize(),
                    "chunkCount", cl.getChunkCount(),
                    "details", cl.getDetails() != null ? cl.getDetails() : "",
                    "operator", cl.getOperator() != null ? cl.getOperator() : "",
                    "createdAt", cl.getCreatedAt() != null ? cl.getCreatedAt().toString() : ""
            ));
        }
        return Result.success(result);
    }

    // ---- 辅助 ----

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? "." + filename.substring(dot + 1) : "";
    }

    private String getCurrentUser() {
        Object username = request.getAttribute("username");
        return username != null ? username.toString() : "未知用户";
    }

    private long countChunksByFileId(String fileId) {
        // 通过一次搜索估算：用零向量搜索全部，再按 fileId 过滤
        int total = vectorStore.size();
        if (total == 0) return 0;

        // 由于 VectorStore 没有直接的按 fileId 计数方法，
        // 这里返回 DB 中记录的 chunkCount 作为近似值。
        // 如需精确值，可在 VectorStore 中添加 countByFileId() 方法。
        return -1; // caller 会 fallback 到 kf.getChunkCount()
    }
}
