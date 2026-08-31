package com.hagent.ai.retrieval;

/**
 * 检索通道类型
 * 预留 HYBRID/GRAPH 位，为后续扩展留扩展点
 *
 * @author H
 */
public enum SearchChannelType {
    /** 向量检索（PgVector HNSW + 原生 filterExpression 权限过滤） */
    VECTOR,
    /** 关键词检索（PG tsvector + GIN，ts_rank 排序） */
    KEYWORD,
    /** 混合（预留，当前由 HybridSearchService 组合 VECTOR + KEYWORD 实现） */
    HYBRID,
    /** 图谱检索（预留） */
    GRAPH
}
