package com.hagent.ai.core;

import com.hagent.ai.retrieval.RetrievedChunk;
import com.hagent.ai.retrieval.SearchChannelResult;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 轻量 RAG 检索链路追踪切面（不建重型 Run/Node 双表，仅日志）
 * <p>
 * 切入 {@link com.hagent.ai.retrieval.HybridSearchService#hybridSearch} 与
 * {@link RagService#searchVisible}，复用 {@code OperationLogAspect} 的 traceId 生成策略：
 * 优先 {@code MDC.get("traceId")}，次之 {@code trace_id}，缺失则生成 UUID 并回写 MDC。
 * 双通道按 {@code vectorChannel}/{@code keywordChannel} 分别记录
 * {@code traceId / channel / latency / topScore} 至日志，供后续证据闸门与观测使用。
 * 不落库、不建 Run/Node 双表，保持轻量预留。
 *
 * @author H
 */
@Slf4j
@Aspect
@Component
public class RagTraceAspect {

    /**
     * 统一环绕：覆盖混合检索入口、可视检索入口及底层向量/关键词通道
     * <p>
     * 要求切点必须包含 {@code HybridSearchService.hybridSearch} 与 {@code RagService.searchVisible}，
     * 同时扩展至 {@code VectorSearchChannel.search}/{@code KeywordSearchChannel.search} 以产出
     * 双通道的 {@code vectorChannel/latency/topScore} 与 {@code keywordChannel/latency/topScore} 日志。
     */
    @Around("execution(* com.hagent.ai.retrieval.HybridSearchService.hybridSearch(..)) "
            + "|| execution(* com.hagent.ai.core.RagService.searchVisible(..)) "
            + "|| execution(* com.hagent.ai.retrieval.VectorSearchChannel.search(..)) "
            + "|| execution(* com.hagent.ai.retrieval.KeywordSearchChannel.search(..))")
    public Object traceAround(ProceedingJoinPoint joinPoint) throws Throwable {
        String traceId = resolveTraceId();
        String channel = resolveChannel(joinPoint);
        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            long latency = System.currentTimeMillis() - start;
            double topScore = extractTopScore(result);
            // 关键观测日志：复用 system_log.trace_id 的 MDC traceId，不建表仅日志
            log.info("traceId={} channel={} latency={} topScore={}", traceId, channel, latency, topScore);
            return result;
        } catch (Throwable ex) {
            long latency = System.currentTimeMillis() - start;
            // 异常分支仍输出 trace 观测，异常原样抛出不吞没
            log.info("traceId={} channel={} latency={} topScore={} error={}", traceId, channel, latency, 0.0, ex.getMessage());
            throw ex;
        }
    }

    /**
     * 解析 traceId，复用 OperationLogAspect 的策略
     * <p>
     * 优先级：MDC traceId -> MDC trace_id -> 随机 UUID（32位去横线）并回写 MDC
     */
    private String resolveTraceId() {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = MDC.get("trace_id");
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
            MDC.put("traceId", traceId);
        }
        return traceId;
    }

    /**
     * 根据切入目标推断通道名，供日志归因
     */
    private String resolveChannel(ProceedingJoinPoint joinPoint) {
        String target = joinPoint.getTarget() != null ? joinPoint.getTarget().getClass().getSimpleName() : "";
        String method = joinPoint.getSignature().getName();
        // 底层通道：直接以通道名作为 channel
        if (target.contains("VectorSearchChannel")) {
            return "vectorChannel";
        }
        if (target.contains("KeywordSearchChannel")) {
            return "keywordChannel";
        }
        // 混合检索入口：hybrid 为融合后总通道，仍单独记录以观测端到端延迟
        if (target.contains("HybridSearchService") && "hybridSearch".equals(method)) {
            return "hybrid";
        }
        // RagService 可视检索：本质为向量通道的前置权限封装
        if (target.contains("RagService") && "searchVisible".equals(method)) {
            return "vectorChannel";
        }
        // 兜底：方法名
        return method;
    }

    /**
     * 提取 topScore（通道最高分），兼容 List&lt;Document&gt; 与 SearchChannelResult
     */
    private double extractTopScore(Object result) {
        try {
            if (result instanceof SearchChannelResult r) {
                List<RetrievedChunk> chunks = r.getChunks();
                if (chunks == null || chunks.isEmpty()) {
                    return 0.0;
                }
                double max = 0.0;
                for (RetrievedChunk c : chunks) {
                    if (c != null && c.getScore() > max) {
                        max = c.getScore();
                    }
                }
                return max;
            }
            if (result instanceof List<?> list) {
                if (list.isEmpty()) {
                    return 0.0;
                }
                // 假设为 List<Document>
                double max = 0.0;
                for (Object o : list) {
                    if (o instanceof Document doc) {
                        double score = extractDocumentScore(doc);
                        if (score > max) {
                            max = score;
                        }
                    }
                }
                return max;
            }
        } catch (Exception e) {
            log.debug("提取 topScore 失败: {}", e.getMessage());
        }
        return 0.0;
    }

    /**
     * 提取单篇 Document 的分数，兼容 Document.getScore() 与 metadata distance/score
     */
    private double extractDocumentScore(Document doc) {
        if (doc == null) {
            return 0.0;
        }
        Double raw = doc.getScore();
        if (raw != null && !Double.isNaN(raw) && !Double.isInfinite(raw) && raw != 0.0) {
            // PgVectorStore 在 2.0.1 中 score 为相似度，0 为异常值需回退到 metadata
            double v = raw.doubleValue();
            if (v != 0.0) {
                return v;
            }
        }
        if (doc.getMetadata() != null) {
            Object d = doc.getMetadata().get("distance");
            if (d instanceof Number n) {
                return n.doubleValue();
            }
            Object s = doc.getMetadata().get("score");
            if (s instanceof Number n2) {
                return n2.doubleValue();
            }
        }
        return 0.0;
    }
}
