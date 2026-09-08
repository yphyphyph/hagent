package com.hagent.ai.service;

/**
 * 查询补全改写服务（P0-4 轻量版）
 * 在知识库检索前把不完整、口语化或含指代的查询改写为可独立检索的完整查询，
 * 并应用 system_config 同义词映射（口语词 → 文档用语），提升多轮场景召回质量
 *
 * @author H
 */
public interface QueryRewriteService {

    /**
     * 改写检索查询
     * 处理顺序：同义词映射（零成本，永远生效）→ LLM 指代补全（有会话历史且开关开启时，一轮调用）
     * 任一环节失败均静默回退原查询，不影响检索主链路
     *
     * @param query 原始检索查询（通常为模型工具调用传入的 query）
     * @return 改写后的查询；无法改写时返回原查询（trim 后）
     * @author H
     */
    String rewrite(String query);
}
