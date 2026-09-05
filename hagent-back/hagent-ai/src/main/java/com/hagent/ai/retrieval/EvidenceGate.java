package com.hagent.ai.retrieval;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 证据闸门（精排后阈值过滤）
 * 当最高精排分低于阈值时整批丢弃，避免低相关证据污染生成
 *
 * @author H
 */
public final class EvidenceGate {

    /** 精排最低分阈值，低于此分整批丢弃 */
    private static final double MIN_RERANK_SCORE = 0.2;

    private EvidenceGate() {
    }

    /**
     * 判断是否应丢弃整批精排结果
     * 对标 RAGent EvidenceGatePostProcessor“无分可读一律放行”：全批均无 rerank_score
     * （null/非数字）时说明重排未生效或上游未打分，此时直接返回 false 放行，避免无分恒丢
     *
     * @param reranked 精排后文档（metadata 需含 rerank_score）
     * @return true 表示整批丢弃，false 表示保留
     * @author H
     */
    public static boolean shouldDiscard(List<Document> reranked) {
        if (reranked == null || reranked.isEmpty()) {
            return true;
        }
        // 无分放行：全批均无有效 rerank_score（null/非数字）时直接放行
        boolean hasScored = false;
        double max = Double.NEGATIVE_INFINITY;
        for (Document d : reranked) {
            Object v = d.getMetadata() != null ? d.getMetadata().get("rerank_score") : null;
            Double score = parseScore(v);
            if (score != null) {
                hasScored = true;
                if (score > max) {
                    max = score;
                }
            }
        }
        if (!hasScored) {
            return false;
        }
        return max < MIN_RERANK_SCORE;
    }

    /**
     * 解析单文档 rerank_score，非数字返回 null
     *
     * @param v 元数据原始值
     * @return 分数或 null（无分）
     * @author H
     */
    private static Double parseScore(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }
}
