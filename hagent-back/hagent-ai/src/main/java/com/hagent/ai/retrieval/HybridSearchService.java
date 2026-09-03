package com.hagent.ai.retrieval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 混合检索门面（双路并行 + RRF + 去重 + 截断）
 * 双路：VectorSearchChannel + KeywordSearchChannel，单路异常/超时降级为单通道
 *
 * @author H
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridSearchService {

    private final VectorSearchChannel vectorChannel;
    private final KeywordSearchChannel keywordChannel;
    private final RrfFusionProcessor fusionProcessor;
    private final HybridSearchProperties properties;
    private final RerankingService rerankingService;

    /**
     * 混合检索（业务入口，返回 Document 列表以复用现有溯源链路）
     *
     * @param context 检索上下文
     * @param topK    最终截回数
     * @return 融合后按 RRF 得分降序的 Document
     */
    public List<Document> hybridSearch(SearchContext context, int topK) {
        long start = System.currentTimeMillis();

        // 短路：关键词禁用且向量通道始终启用，直接单通道（仍走精排闸门以保持一致性）
        boolean keywordEnabled = keywordChannel.isEnabled(context);
        if (!properties.isEnabled() || !keywordEnabled) {
            SearchChannelResult vectorResult = vectorChannel.search(context);
            List<RetrievedChunk> chunks = vectorResult.getChunks();
            log.info("混合检索单通道（向量）: query={}, topK={}, chunks={}, latencyMs={}",
                    context.getQuery(), topK, chunks.size(), vectorResult.getLatencyMs());
            List<Document> docs = toDocuments(chunks, topK);
            List<Document> reranked = rerankingService.rerank(context.getQuery(), docs);
            if (EvidenceGate.shouldDiscard(reranked)) {
                log.info("证据闸门丢弃（单通道）: query={}, reranked={}", context.getQuery(), reranked.size());
                return List.of();
            }
            if (reranked.size() > topK) {
                return reranked.subList(0, topK);
            }
            return reranked;
        }

        // 双路并行
        Executor direct = Runnable::run; // 同线程执行，避免线程池依赖；后续可换专用 Executor
        CompletableFuture<SearchChannelResult> vectorFuture = CompletableFuture
                .supplyAsync(() -> vectorChannel.search(context), direct)
                .orTimeout(properties.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    log.warn("向量通道超时/异常，降级为关键词单通道: {}", ex.getMessage());
                    return vectorChannel.emptyResult(properties.getTimeoutMs());
                });

        CompletableFuture<SearchChannelResult> keywordFuture = CompletableFuture
                .supplyAsync(() -> keywordChannel.search(context), direct)
                .orTimeout(properties.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    log.warn("关键词通道超时/异常，降级为向量单通道: {}", ex.getMessage());
                    return keywordChannel.emptyResult(properties.getTimeoutMs());
                });

        List<SearchChannelResult> results = new ArrayList<>(2);
        try {
            CompletableFuture.allOf(vectorFuture, keywordFuture).join();
            results.add(vectorFuture.join());
            results.add(keywordFuture.join());
        } catch (Exception e) {
            log.warn("混合检索并行异常，降级为可用通道: {}", e.getMessage());
            if (vectorFuture.isDone() && !vectorFuture.isCompletedExceptionally()) {
                try { results.add(vectorFuture.join()); } catch (Exception ignored) {}
            }
            if (keywordFuture.isDone() && !keywordFuture.isCompletedExceptionally()) {
                try { results.add(keywordFuture.join()); } catch (Exception ignored) {}
            }
        }

        // 去重 + RRF 融合
        List<RetrievedChunk> fused = fusionProcessor.fuse(results, topK);
        long latency = System.currentTimeMillis() - start;
        log.info("混合检索完成（融合后）: query={}, topK={}, fused={}, latencyMs={}",
                context.getQuery(), topK, fused.size(), latency);
        // RRF 后精排 + 证据闸门：精排按 query 重排，最高分 <0.2 整批丢弃，保留 RagService.retainHitMetadata 所需 Document 形态
        List<Document> docs = toDocuments(fused, topK);
        List<Document> reranked = rerankingService.rerank(context.getQuery(), docs);
        if (EvidenceGate.shouldDiscard(reranked)) {
            log.info("证据闸门丢弃（双路）: query={}, topK={}, reranked={}, latencyMs={}",
                    context.getQuery(), topK, reranked.size(), System.currentTimeMillis() - start);
            return List.of();
        }
        if (reranked.size() > topK) {
            reranked = reranked.subList(0, topK);
        }
        log.info("混合检索精排闸门后: query={}, topK={}, reranked={}, latencyMs={}",
                context.getQuery(), topK, reranked.size(), System.currentTimeMillis() - start);
        return reranked;
    }

    /**
     * 便捷方法（任务清单签名：query/topK/threshold/userId 四参）
     * <p>
     * 内部并行双路：向量通道复用原生 filterExpression 权限过滤 + 关键词通道 JDBC tsvector 权限过滤，
     * 结果转 Document，经 RrfFusionProcessor.rrf(k=20) 合并去重取 topK，保留 RagService.retainHitMetadata 透传
     *
     * @param query     查询
     * @param topK      截回数
     * @param threshold 向量阈值
     * @param userId    当前用户
     * @return Document 列表
     * @author H
     */
    public List<Document> hybridSearch(String query, int topK, double threshold, Long userId) {
        return hybridSearch(query, topK, threshold, userId, null);
    }

    /**
     * 便捷方法（与 RagService.searchAccessible 同签名风格）
     *
     * @param query     查询
     * @param topK      截回数
     * @param threshold 向量阈值（透传给 SearchContext）
     * @param userId    当前用户
     * @param fileIds   限定文件
     * @return Document 列表
     * @author H
     */
    public List<Document> hybridSearch(String query, int topK, double threshold, Long userId, List<String> fileIds) {
        SearchContext context = SearchContext.builder()
                .query(query)
                .topK(Math.max(properties.getKeyword().getTopK(), topK))
                .threshold(threshold)
                .userId(userId)
                .fileIds(fileIds)
                .build();
        // threshold 仅向量通道用，关键词通道忽略
        return hybridSearch(context, topK);
    }

    private List<Document> toDocuments(List<RetrievedChunk> chunks, int topK) {
        List<Document> docs = new ArrayList<>(chunks.size());
        for (RetrievedChunk chunk : chunks) {
            Document doc = chunk.getDocument();
            if (doc != null) {
                docs.add(doc);
            } else {
                Document d = new Document(chunk.getId(), chunk.getContent(), chunk.getMetadata());
                docs.add(d);
            }
        }
        if (docs.size() > topK) {
            return docs.subList(0, topK);
        }
        return docs;
    }
}
