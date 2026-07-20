package com.streetlight.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存向量存储 + JSON 文件持久化。
 *
 * 每个文档关联一个 fileId，支持按文件删除和去重。
 * 启动时从 JSON 文件恢复，关闭时自动保存。后续可平滑切换到 PGvector / Redis Stack / Milvus。
 */
@Slf4j
@Component
public class VectorStore {

    private final List<Document> documents = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper;

    @Value("${embedding.vector-store-path:data/knowledge-vector-store.json}")
    private String storePath;

    public VectorStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 添加文档向量，关联到指定文件 */
    public synchronized void add(String fileId, float[] embedding, String content,
                    String fileName, String fileType, int chunkIndex) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("fileId", fileId);
        metadata.put("fileName", fileName);
        metadata.put("fileType", fileType);
        metadata.put("chunkIndex", String.valueOf(chunkIndex));

        documents.add(new Document(UUID.randomUUID().toString(), fileId,
                embedding, content, metadata));
    }

    /**
     * 余弦相似度检索 Top-K。
     *
     * @param queryEmbedding 查询向量
     * @param topK           返回条数
     * @return 按相似度降序排列的结果
     */
    public List<SearchResult> search(float[] queryEmbedding, int topK) {
        if (documents.isEmpty()) return List.of();

        PriorityQueue<SearchResult> pq = new PriorityQueue<>(
                Comparator.comparingDouble(SearchResult::score));

        for (Document doc : documents) {
            double sim = cosineSimilarity(queryEmbedding, doc.embedding);
            SearchResult sr = new SearchResult(
                    doc.fileId,
                    doc.metadata.getOrDefault("fileName", "未知文件"),
                    doc.content,
                    doc.metadata,
                    sim);
            pq.offer(sr);
            if (pq.size() > topK) {
                pq.poll();
            }
        }

        List<SearchResult> results = new ArrayList<>(pq);
        results.sort((a, b) -> Double.compare(b.score, a.score));
        return results;
    }

    /** 删除指定文件的所有向量 */
    public synchronized int deleteByFileId(String fileId) {
        int before = documents.size();
        documents.removeIf(doc -> fileId.equals(doc.fileId));
        int removed = before - documents.size();
        if (removed > 0) {
            log.info("已删除 fileId={} 的 {} 条向量", fileId, removed);
        }
        return removed;
    }

    // ---- 持久化 ----

    public int size() { return documents.size(); }

    public synchronized void clear() { documents.clear(); }

    @PreDestroy
    public void onShutdown() {
        if (!documents.isEmpty()) {
            saveToFile();
        }
    }

    /** 保存到 JSON 文件 */
    public synchronized void saveToFile() {
        try {
            List<DocumentJson> list = documents.stream()
                    .map(DocumentJson::from)
                    .toList();
            File file = new File(storePath);
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            objectMapper.writeValue(file, list);
            log.info("向量存储已持久化到 {}，共 {} 条", storePath, list.size());
        } catch (Exception e) {
            log.error("保存向量存储失败: {}", e.getMessage());
        }
    }

    /** 从 JSON 文件加载（启动时调用） */
    public synchronized void loadFromFile() {
        File file = new File(storePath);
        if (!file.exists()) {
            log.info("向量存储文件不存在，跳过加载: {}", storePath);
            return;
        }
        try {
            List<DocumentJson> list = objectMapper.readValue(file,
                    new TypeReference<List<DocumentJson>>() {});
            documents.clear();
            for (DocumentJson dj : list) {
                documents.add(dj.toDocument());
            }
            log.info("从 {} 加载了 {} 条向量", storePath, documents.size());
        } catch (Exception e) {
            log.error("加载向量存储失败: {}", e.getMessage());
        }
    }

    // ==================== 内部类型 ====================

    private record Document(String id, String fileId, float[] embedding,
                            String content, Map<String, String> metadata) {}

    public record SearchResult(String fileId, String fileName, String content,
                               Map<String, String> metadata, double score) {}

    /** JSON 序列化 POJO */
    private record DocumentJson(String id, String fileId, List<Double> embedding,
                                String content, Map<String, String> metadata) {
        static DocumentJson from(Document doc) {
            List<Double> emb = new ArrayList<>(doc.embedding.length);
            for (float v : doc.embedding) emb.add((double) v);
            return new DocumentJson(doc.id, doc.fileId, emb, doc.content, doc.metadata);
        }

        Document toDocument() {
            float[] emb = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) emb[i] = embedding.get(i).floatValue();
            return new Document(id != null ? id : UUID.randomUUID().toString(),
                    fileId, emb, content, metadata != null ? metadata : Map.of());
        }
    }

    // ==================== 余弦相似度 ====================

    private static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
