package com.hagent.ai.retrieval;

/**
 * 检索通道抽象（最小可扩展：向量 / 关键词 各一实现，后续图谱/联网仅新增实现即可）
 * 参考 RAGent SearchChannel，HAgent 仅做 VECTOR + KEYWORD 两通道，不引入 supplement/意图树
 *
 * @author H
 */
public interface SearchChannel {

    /**
     * 通道名称（用于日志与归因）
     *
     * @return 通道名
     */
    String getName();

    /**
     * 是否在当前上下文启用
     *
     * @param context 检索上下文
     * @return true 启用
     */
    boolean isEnabled(SearchContext context);

    /**
     * 执行检索
     *
     * @param context 检索上下文
     * @return 通道结果
     */
    SearchChannelResult search(SearchContext context);

    /**
     * 通道类型
     *
     * @return 类型枚举
     */
    SearchChannelType getType();

    /**
     * 空结果（超时/禁用时用于降级为单通道）
     *
     * @param latencyMs 耗时
     * @return 空结果
     */
    default SearchChannelResult emptyResult(long latencyMs) {
        return SearchChannelResult.builder()
                .channelType(getType())
                .channelName(getName())
                .chunks(java.util.List.of())
                .latencyMs(latencyMs)
                .build();
    }
}
