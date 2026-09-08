package com.hagent.ai.core;

import com.hagent.knowledge.entity.KnowledgeFile;
import com.hagent.knowledge.mapper.KnowledgeFileMapper;
import com.hagent.common.context.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final com.hagent.ai.retrieval.HybridSearchService hybridSearchService;

    /** 知识库文件 Mapper（可选注入，用于 file_name 兜底查询） */
    @Autowired(required = false)
    @Lazy
    private KnowledgeFileMapper knowledgeFileMapper;

    /** 命中元数据 ThreadLocal（同请求线程内透传，key：file_id、chunk_id、score、file_name、snippet） */
    private static final ThreadLocal<List<Map<String, Object>>> LAST_HITS = new ThreadLocal<>();

    /** snippet 最大字符数（避免过长撑爆上下文/前端） */
    private static final int SNIPPET_MAX_LENGTH = 500;

    /** chunk_id 前缀解析正则：file-<fileId>-chunk-<index> */
    private static final Pattern CHUNK_ID_PATTERN = Pattern.compile("^file-(.+?)-chunk-.*$");

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
            // 保留命中元数据（供上层组装来源引用）
            retainHitMetadata(results);
            incrementSearchCount();
            log.info("RAG搜索完成: query={}, results={}", query, results.size());
            for (Document doc : results) {
                log.debug("搜索结果: id={}, textLength={}, metadata={}", doc.getId(), doc.getText().length(), doc.getMetadata());
            }
            return results;
        } catch (Exception e) {
            log.error("RAG搜索失败: query={}", query, e);
            // 异常时清空历史命中，避免脏数据被误用
            LAST_HITS.remove();
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
            // 保留命中元数据（带筛选分支）
            retainHitMetadata(results);
            incrementSearchCount();
            log.info("RAG带筛选搜索: query={}, topK={}, fileIds={}, results={}",
                    query, topK, fileIds, results.size());
            return results;
        } catch (Exception e) {
            log.error("RAG带筛选搜索失败: query={}", query, e);
            LAST_HITS.remove();
            return List.of();
        }
    }

    /**
     * 按用户可见性搜索相似文档（PostgreSQL 向量库原生过滤）
     * <p>
     * 权限已写入向量元数据（visibility/user_id/file_id 与 VectorStoreServiceImpl 一致）：
     * 向量库原生 filterExpression 第一档过滤，仅召回 visibility == 1（公有）或 user_id == 当前用户的命中，
     * 无用户身份时仅公有可见。公私切换由 vector_store 同库 jsonb_set 同步即时生效，无需重嵌入。
     * PG 场景下 chunk_id 可能为 uuid，retainHitMetadata 已做 file_id/chunk_id 兜底。
     *
     * @param query     搜索查询文本
     * @param topK      最终返回的 Top 结果数量
     * @param threshold 相似度阈值
     * @return 当前用户可见的匹配文档列表
     * @author H
     */
    public List<Document> searchAccessible(String query, int topK, double threshold) {
        try {
            Long userId = resolveCurrentUserId();
            List<Document> results = hybridSearchService.hybridSearch(query, topK, threshold, userId, null);
            if (results == null || results.isEmpty()) {
                // 证据门控已判定"内容不相关"：跳过单向量回退——回退会把低相关噪声重新捡回给模型，
                // 诱发"换词重搜→又被丢弃→再换词"的发散循环；直接返回空，由工具层向模型明示"知识库无此内容"
                if (com.hagent.ai.retrieval.HybridSearchService.wasGateDiscarded()) {
                    log.info("证据门控判定无相关内容，跳过单向量回退: query={}", query);
                    return List.of();
                }
                // 降级：混合检索未返回时回退到原单向量路径（兼容检索层异常）
                log.warn("混合检索未返回结果，尝试单向量回退: query={}", query);
                VectorStore vectorStore = aiModelProvider.getVectorStore();
                if (vectorStore == null) {
                    return List.of();
                }
                String filter = userId != null ? "visibility == 1 || user_id == " + userId : "visibility == 1";
                SearchRequest request = SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .similarityThreshold(threshold)
                        .filterExpression(filter)
                        .build();
                results = vectorStore.similaritySearch(request);
            }
            retainHitMetadata(results);
            incrementSearchCount();
            log.info("RAG可见性搜索完成（混合 RRF）: query={}, topK={}, accessible={}", query, topK, results.size());
            return results;
        } catch (Exception e) {
            log.error("RAG可见性搜索失败: query={}", query, e);
            LAST_HITS.remove();
            return List.of();
        }
    }

    /**
     * 按用户可见性搜索（fileId 精确模式重载）：fileId 非空时锁定单一知识库文件，
     * 由 HybridSearchService 跳过关键词通道仅走向量通道（file_id == '<fileId>' 过滤）；
     * fileId 为空时委托三参版本，行为完全一致
     * <p>
     * 精确模式下不做"无 file_id 过滤"的回退——回退会破坏锁定语义（混入其他文件命中），
     * 检索层单向量通道本身已覆盖降级路径
     *
     * @param query     搜索查询文本
     * @param topK      最终返回的 Top 结果数量
     * @param threshold 相似度阈值
     * @param fileId    精确锁定知识库文件ID（可空）
     * @return 当前用户可见且属于该文件的匹配文档列表
     * @author H
     */
    public List<Document> searchAccessible(String query, int topK, double threshold, Long fileId) {
        if (fileId == null) {
            return searchAccessible(query, topK, threshold);
        }
        try {
            Long userId = resolveCurrentUserId();
            // fileId 下沉到混合检索门面（精确模式判定集中处），本层仅透传不复制过滤逻辑
            List<Document> results = hybridSearchService.hybridSearch(query, topK, threshold, userId, null, fileId);
            retainHitMetadata(results);
            incrementSearchCount();
            log.info("RAG精确搜索完成（fileId={}）: query={}, topK={}, hits={}", fileId, query, topK, results.size());
            return results;
        } catch (Exception e) {
            log.error("RAG精确搜索失败: query={}, fileId={}", query, fileId, e);
            LAST_HITS.remove();
            return List.of();
        }
    }

    /**
     * 按用户可见性搜索（清单别名，委托同 searchAccessible，保持 filterExpression 权限前置）
     * <p>
     * 仅改内部委托为 hybridSearchService.hybridSearch(...)，不改 filterExpression 权限前置口径
     *
     * @param query     搜索查询文本
     * @param topK      Top 结果数量
     * @param threshold 相似度阈值
     * @return 当前用户可见的匹配文档列表
     * @author H
     */
    public List<Document> searchVisible(String query, int topK, double threshold) {
        return searchAccessible(query, topK, threshold);
    }

    /**
     * 解析当前用户ID：聊天链路（UserContextHolder）优先，HTTP 请求链路（UserContext）兜底
     */
    private Long resolveCurrentUserId() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            userId = UserContext.getUserId();
        }
        return userId;
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
     * 保留搜索命中元数据到 ThreadLocal（同线程内透传给上层 SSE 推送）
     * 字段：file_id、chunk_id、score、file_name、snippet
     * <p>
     * 兼容逻辑：
     * 1. 记录实际 Document metadata keys 以便排查键名不匹配问题（样例日志）
     * 2. file_id / file_name 兼容多种键名：file_id、fileId、file-id、file_name、fileName、source 等
     * 3. file_name 缺失时，尝试从 chunk_id 前缀 file-&lt;fileId&gt;-chunk-&lt;index&gt; 解析 file_id 并通过 KnowledgeFileMapper 兜底查询
     *
     * @param documents 命中文档列表
     * @author H
     */
    private void retainHitMetadata(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            LAST_HITS.set(List.of());
            return;
        }
        // 打印样例元数据键，便于排查 Unknown file 问题
        try {
            Document sample = documents.get(0);
            Map<String, Object> sampleMeta = sample.getMetadata() != null ? sample.getMetadata() : Map.of();
            log.info("RAG命中元数据样例: docId={}, metadataKeys={}, metadata={}, score={}",
                    sample.getId(), sampleMeta.keySet(), sampleMeta, sample.getScore());
        } catch (Exception e) {
            log.warn("记录RAG命中样例日志失败", e);
        }
        List<Map<String, Object>> hits = new ArrayList<>(documents.size());
        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata() != null ? doc.getMetadata() : Map.of();
            Map<String, Object> hit = new LinkedHashMap<>();
            // file_id：兼容多种键名，优先 file_id，其次 fileId / file-id
            Object fileId = firstNonNull(
                    meta.get("file_id"),
                    meta.get("fileId"),
                    meta.get("file-id"),
                    meta.get("fileID"),
                    meta.get("source") // 兜底：部分实现可能用 source 存 file_id
            );
            // 若元数据中仍无 file_id，尝试从 chunk_id 前缀解析
            String chunkId = doc.getId();
            if (fileId == null || String.valueOf(fileId).isBlank()) {
                String parsed = extractFileIdFromChunkId(chunkId);
                if (parsed != null) {
                    fileId = parsed;
                    log.debug("file_id 从 chunk_id 前缀解析: chunkId={}, parsedFileId={}", chunkId, parsed);
                }
            }
            hit.put("file_id", fileId);
            // chunk_id：优先文档ID（file-<fileId>-chunk-<i>），兜底 chunk_index / chunkId / chunk_id
            if ((chunkId == null || chunkId.isBlank())) {
                Object chunkIdx = firstNonNull(meta.get("chunk_index"), meta.get("chunkIndex"), meta.get("chunk_id"), meta.get("chunkId"));
                if (chunkIdx != null) {
                    chunkId = String.valueOf(chunkIdx);
                }
            }
            hit.put("chunk_id", chunkId);
            // score：优先 Document.score，兜底元数据 distance/score
            Object score = doc.getScore();
            if (score == null) {
                score = meta.get("distance");
            }
            if (score == null) {
                score = meta.get("score");
            }
            hit.put("score", score);
            // file_name：兼容 file_name / fileName / source 等，缺失时通过 file_id 兜底查询 knowledge_file 表
            Object fileName = firstNonNull(
                    meta.get("file_name"),
                    meta.get("fileName"),
                    meta.get("file-name"),
                    meta.get("sourceFileName"),
                    meta.get("filename")
            );
            if (fileName == null || String.valueOf(fileName).isBlank()) {
                // 尝试通过 file_id 兜底查询
                String fileIdStr = fileId != null ? String.valueOf(fileId) : null;
                if (fileIdStr == null || fileIdStr.isBlank()) {
                    // 最后尝试从 chunk_id 解析
                    fileIdStr = extractFileIdFromChunkId(chunkId);
                }
                String resolved = resolveFileNameViaMapper(fileIdStr);
                if (resolved != null) {
                    fileName = resolved;
                    log.info("file_name 兜底查询成功: fileId={}, fileName={}", fileIdStr, resolved);
                } else {
                    log.warn("file_name 兜底查询失败: fileId={}, chunkId={}, metadataKeys={}", fileIdStr, chunkId, meta.keySet());
                }
            }
            hit.put("file_name", fileName);
            // snippet：原文前缀截断，避免过长
            String text = doc.getText() != null ? doc.getText() : "";
            String snippet = text.length() > SNIPPET_MAX_LENGTH
                    ? text.substring(0, SNIPPET_MAX_LENGTH) + "..."
                    : text;
            hit.put("snippet", snippet);
            hits.add(hit);
        }
        // 汇总日志：统计 file_name 缺失情况
        long nullNameCount = hits.stream().filter(h -> h.get("file_name") == null || String.valueOf(h.get("file_name")).isBlank()).count();
        if (nullNameCount > 0) {
            log.warn("RAG命中中 file_name 仍为 null/空的条数: {}/{}，已记录样例元数据，请检查向量存储的元数据键是否一致(file_id/file_name/chunk_index)", nullNameCount, hits.size());
        } else {
            log.info("RAG命中 file_name 均已解析: count={}", hits.size());
        }
        LAST_HITS.set(hits);
    }

    /**
     * 取首个非空值
     */
    private static Object firstNonNull(Object... values) {
        for (Object v : values) {
            if (v != null && !String.valueOf(v).isBlank()) {
                return v;
            }
        }
        return null;
    }

    /**
     * 从 chunk_id 前缀解析 file_id，形如 file-2075228410665365508-chunk-71
     *
     * @param chunkId 文档ID
     * @return fileId 字符串，无法解析时返回 null
     */
    private static String extractFileIdFromChunkId(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            return null;
        }
        Matcher m = CHUNK_ID_PATTERN.matcher(chunkId);
        if (m.matches()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * 通过 KnowledgeFileMapper 兜底查询 file_name
     *
     * @param fileIdStr file_id 字符串（应为数值型）
     * @return fileName，查询不到或异常时返回 null
     */
    private String resolveFileNameViaMapper(String fileIdStr) {
        if (fileIdStr == null || fileIdStr.isBlank()) {
            return null;
        }
        if (knowledgeFileMapper == null) {
            log.debug("KnowledgeFileMapper 未注入，跳过兜底查询: fileId={}", fileIdStr);
            return null;
        }
        try {
            long id = Long.parseLong(fileIdStr);
            KnowledgeFile kf = knowledgeFileMapper.selectById(id);
            if (kf != null) {
                return kf.getFileName();
            }
            log.debug("KnowledgeFileMapper 查询为空: fileId={}", fileIdStr);
        } catch (NumberFormatException e) {
            log.warn("fileId 非数值，无法通过 KnowledgeFileMapper 查询: fileId={}", fileIdStr);
        } catch (Exception e) {
            log.warn("KnowledgeFileMapper 兜底查询异常: fileId={}", fileIdStr, e);
        }
        return null;
    }

    /**
     * 获取当前线程最近一次搜索的命中元数据
     *
     * @return 命中列表（只读，不为null）
     * @author H
     */
    public List<Map<String, Object>> getLastHits() {
        List<Map<String, Object>> hits = LAST_HITS.get();
        return hits != null ? hits : List.of();
    }

    /**
     * 清理当前线程的命中元数据（防止线程复用时串扰）
     *
     * @author H
     */
    public void clearLastHits() {
        LAST_HITS.remove();
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
