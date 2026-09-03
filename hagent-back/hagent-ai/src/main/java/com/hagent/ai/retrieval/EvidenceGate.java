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
     *
     * @param reranked 精排后文档（metadata 需含 rerank_score）
     * @return true 表示整批丢弃，false 表示保留
     */
    public static boolean shouldDiscard(List<Document> reranked) {
        if (reranked == null || reranked.isEmpty()) {
            return true;
        }
        double max = reranked.stream()
                .mapToDouble(d -> {
                    Object v = d.getMetadata() != null ? d.getMetadata().getOrDefault("rerank_score", 0.0) : 0.0;
                    if (v instanceof Number n) {
                        return n.doubleValue();
                    }
                    try {
                        return Double.parseDouble(String.valueOf(v));
                    } catch (Exception e) {
                        return 0.0;
                    }
                })
                .max()
                .orElse(0.0);
        return max < MIN_RERANK_SCORE;
    }
}
