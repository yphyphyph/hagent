package com.hagent.ai.retrieval;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 检索上下文（双通道共享：查询、权限、候选数）
 *
 * @author H
 */
@Data
@Builder
public class SearchContext {

    /** 查询文本 */
    private String query;

    /** 候选召回数（每通道 topK，融合后再截回最终 topK） */
    private int topK;

    /** 相似度阈值（向量通道用，关键词通道忽略） */
    private double threshold;

    /** 当前用户 ID（可见性过滤用，null 表示匿名仅公有） */
    private Long userId;

    /** 限定文件 ID 列表（会话 rag_file_ids 场景，可为 null） */
    private List<String> fileIds;
}
