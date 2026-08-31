package com.hagent.ai.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.document.Document;

import java.util.Map;

/**
 * 召回块（通道间统一载体，融合时按 file_id + chunk_index 去重）
 *
 * @author H
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievedChunk {

    /** 块 ID（Document.getId()，PG 下为 uuid） */
    private String id;

    /** 块内容 */
    private String content;

    /** 元数据（原 Document metadata 透传：file_id/file_name/chunk_index/visibility/user_id/source） */
    private Map<String, Object> metadata;

    /** 通道原始分（向量 cosine 距离转分 / ts_rank） */
    private double score;

    /** 文档对象（可选，复用 trans） */
    private transient Document document;

    /**
     * 去重键：file_id + chunk_index（与 RagService.retainHitMetadata 的去重口径一致）
     *
     * @return 去重键
     */
    public String dedupKey() {
        if (metadata == null) {
            return id != null ? id : "";
        }
        Object fileId = metadata.get("file_id");
        Object chunkIdx = metadata.get("chunk_index");
        if (fileId == null) {
            fileId = metadata.get("fileId");
        }
        if (chunkIdx == null) {
            chunkIdx = metadata.get("chunkIndex");
        }
        return String.valueOf(fileId) + "#" + String.valueOf(chunkIdx);
    }

    /**
     * 从 Document 构造
     *
     * @param doc 文档
     * @return 块
     */
    public static RetrievedChunk fromDocument(Document doc) {
        // PG PgVectorStore 的分数在 metadata 的 distance 中，Document.getScore() 在 2.0.1 为压缩分，优先读正值
        Double raw = doc.getScore();
        double score = 0.0;
        if (raw != null && !Double.isNaN(raw) && !Double.isInfinite(raw) && raw != 0.0) {
            score = raw;
        } else if (doc.getMetadata() != null) {
            Object d = doc.getMetadata().get("distance");
            if (d instanceof Number n) {
                score = n.doubleValue();
            } else if (doc.getMetadata().get("score") instanceof Number n2) {
                score = n2.doubleValue();
            }
        }
        return RetrievedChunk.builder()
                .id(doc.getId())
                .content(doc.getText())
                .metadata(doc.getMetadata())
                .score(score)
                .document(doc)
                .build();
    }
}
