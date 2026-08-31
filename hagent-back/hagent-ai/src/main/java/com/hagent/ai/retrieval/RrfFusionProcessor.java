package com.hagent.ai.retrieval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF 融合器（倒数排名融合）
 * 参考 RAGent FusionPostProcessor，HAgent 简化版：仅 VECTOR + KEYWORD 两通道
 * 公式：score(d) = Σ weight_c / (k + rank_c)，rank 1-indexed 按通道原始顺序
 *
 * @author H
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RrfFusionProcessor {

    private final HybridSearchProperties properties;

    /**
     * 融合多通道结果
     *
     * @param results  各通道结果（已按通道原始 score 降序）
     * @param topK     最终截回数
     * @return 融合后按 RRF 得分降序的块
     */
    public List<RetrievedChunk> fuse(List<SearchChannelResult> results, int topK) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        // 单通道：直接透传，保留原始顺序
        List<SearchChannelResult> nonEmpty = results.stream()
                .filter(r -> r.getChunks() != null && !r.getChunks().isEmpty())
                .toList();
        if (nonEmpty.size() == 1) {
            List<RetrievedChunk> single = nonEmpty.get(0).getChunks();
            return single.size() <= topK ? new ArrayList<>(single) : new ArrayList<>(single.subList(0, topK));
        }
        if (nonEmpty.isEmpty()) {
            return List.of();
        }

        int k = properties.getRrfK();
        double wVector = properties.getWeights().getVector();
        double wKeyword = properties.getWeights().getKeyword();

        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, RetrievedChunk> dedup = new LinkedHashMap<>();

        for (SearchChannelResult result : nonEmpty) {
            double weight = result.getChannelType() == SearchChannelType.VECTOR ? wVector : wKeyword;
            List<RetrievedChunk> chunks = result.getChunks();
            for (int i = 0; i < chunks.size(); i++) {
                RetrievedChunk chunk = chunks.get(i);
                String key = chunk.dedupKey();
                // 去重：保留首现
                dedup.putIfAbsent(key, chunk);
                int rank = i + 1; // 1-indexed
                double delta = weight / (k + rank);
                rrfScores.merge(key, delta, Double::sum);
            }
        }

        // 按 RRF 得分降序
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(rrfScores.entrySet());
        sorted.sort(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()));

        List<RetrievedChunk> fused = new ArrayList<>(sorted.size());
        for (Map.Entry<String, Double> entry : sorted) {
            RetrievedChunk chunk = dedup.get(entry.getKey());
            if (chunk != null) {
                // 覆盖 score 为 RRF 得分（原始分已用于排名计算）
                chunk.setScore(entry.getValue());
                fused.add(chunk);
            }
        }

        if (fused.size() > topK) {
            fused = fused.subList(0, topK);
        }

        log.info("RRF融合: k={}, weights=({}/{})，输入 {} 通道共 {} 去重后 {} 条，截回 topK={}",
                k, wVector, wKeyword, nonEmpty.size(),
                nonEmpty.stream().mapToInt(r -> r.getChunks().size()).sum(),
                dedup.size(), fused.size());

        return fused;
    }
}
