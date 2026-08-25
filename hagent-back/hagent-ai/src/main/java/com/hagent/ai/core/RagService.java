package com.hagent.ai.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * RAG服务
 * 提供向量搜索和文档管理能力
 *
 * @author H
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    /**
     * embedding 接口单批文本上限（DashScope 兼容接口限制 batch size <= 10，超限报 400）
     */
    private static final int EMBEDDING_BATCH_SIZE = 10;

    /** 知识库检索次数 Redis 计数键（与 KnowledgeServiceImpl 保持一致） */
    private static final String SEARCH_COUNT_REDIS_KEY = "hagent:knowledge:search:count";

    private final AiModelProvider aiModelProvider;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 搜索相似文档
     *
     * @param query     搜索查询文本
     * @param topK      Top结果数量
     * @param threshold 相似度阈值
     * @return 匹配的文档列表
     */
    public List<Document> search(String query, int topK, double threshold) {
        try {
            VectorStore vectorStore = aiModelProvider.getVectorStore();
            if (vectorStore == null) {
                log.warn("VectorStore不可用，RAG搜索已跳过");
                return List.of();
            }
            log.info("开始RAG搜索: query={}, topK={}, threshold={}", query, topK, threshold);
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(threshold)
                    .build();
            log.debug("SearchRequest: {}", request);
            List<Document> results = vectorStore.similaritySearch(request);
            incrementSearchCount();
            log.info("RAG搜索完成: query={}, results={}", query, results.size());
            for (Document doc : results) {
                log.debug("搜索结果: id={}, textLength={}, metadata={}", doc.getId(), doc.getText().length(), doc.getMetadata());
            }
            return results;
        } catch (Exception e) {
            log.error("RAG搜索失败: query={}", query, e);
            return List.of();
        }
    }

    /**
     * 按文件ID筛选搜索相似文档
     *
     * @param query     搜索查询文本
     * @param topK      Top结果数量
     * @param threshold 相似度阈值
     * @param fileIds   要筛选的文件ID列表
     * @return 匹配的文档列表
     */
    public List<Document> search(String query, int topK, double threshold, List<String> fileIds) {
        try {
            VectorStore vectorStore = aiModelProvider.getVectorStore();
            if (vectorStore == null) {
                log.warn("VectorStore不可用，RAG搜索已跳过");
                return List.of();
            }
            SearchRequest.Builder builder = SearchRequest.builder().query(query).topK(topK);
            if (fileIds != null && !fileIds.isEmpty()) {
                // 对每个文件ID进行合法性校验，防止过滤表达式注入
                String filterExpression = fileIds.stream()
                        .map(this::sanitizeFileId)
                        .map(id -> "file_id == '" + id + "'")
                        .reduce((a, b) -> a + " || " + b)
                        .orElse("");
                if (!filterExpression.isEmpty()) {
                    builder.filterExpression(filterExpression);
                }
            }
            List<Document> results = vectorStore.similaritySearch(builder.build());
            incrementSearchCount();
            log.info("RAG带筛选搜索: query={}, topK={}, fileIds={}, results={}",
                    query, topK, fileIds, results.size());
            return results;
        } catch (Exception e) {
            log.error("RAG带筛选搜索失败: query={}", query, e);
            return List.of();
        }
    }

    /**
     * 添加文档到向量存储
     *
     * @param documents 要添加的文档列表
     */
    public void addDocuments(List<Document> documents) {
        VectorStore vectorStore = aiModelProvider.getVectorStore();
        if (vectorStore == null) {
            log.warn("VectorStore不可用，无法添加文档");
            return;
        }
        log.info("开始添加文档到向量存储: count={}, vectorStoreClass={}", documents.size(), vectorStore.getClass().getName());
        for (Document doc : documents) {
            log.info("添加文档: id={}, text={}, metadata={}", doc.getId(), doc.getText(), doc.getMetadata());
        }
        try {
            // embedding 服务单批上限为 10，超限需分批提交
            int total = documents.size();
            for (int from = 0; from < total; from += EMBEDDING_BATCH_SIZE) {
                int to = Math.min(from + EMBEDDING_BATCH_SIZE, total);
                vectorStore.add(documents.subList(from, to));
                log.info("RAG文档批次已添加: {}/{}", to, total);
            }
            log.info("RAG文档已添加成功: count={}", documents.size());
        } catch (Exception e) {
            log.error("添加RAG文档失败: count={}", documents.size(), e);
            throw e;
        }
    }

    /**
     * 按文件ID删除文档
     *
     * @param fileId 要删除的文件ID
     */
    public void deleteByFileId(String fileId) {
        // 校验文件ID合法性，防止过滤表达式注入
        sanitizeFileId(fileId);
        try {
            VectorStore vectorStore = aiModelProvider.getVectorStore();
            if (vectorStore == null) {
                log.warn("VectorStore不可用，无法删除文档: fileId={}", fileId);
                return;
            }
            // 使用过滤表达式按file_id删除
            vectorStore.delete("file_id == '" + fileId + "'");
            log.info("RAG文档已删除: fileId={}", fileId);
        } catch (Exception e) {
            log.error("删除RAG文档失败: fileId={}", fileId, e);
        }
    }

    /**
     * 校验文件ID合法性，只允许字母数字和连字符下划线
     * 防止通过文件ID注入过滤表达式
     *
     * @param fileId 文件ID
     * @return 校验通过的文件ID
     * @throws IllegalArgumentException 当文件ID非法时抛出
     */
    private String sanitizeFileId(String fileId) {
        if (fileId == null || !fileId.matches("^[a-zA-Z0-9_-]+$")) {
            throw new IllegalArgumentException("非法的文件ID: " + fileId);
        }
        return fileId;
    }

    /**
     * 检索成功后自增知识库检索次数计数（Redis）
     * 计数失败不影响检索主流程，仅记录告警
     */
    private void incrementSearchCount() {
        try {
            stringRedisTemplate.opsForValue().increment(SEARCH_COUNT_REDIS_KEY);
        } catch (Exception e) {
            log.warn("知识库检索次数计数失败", e);
        }
    }
}
