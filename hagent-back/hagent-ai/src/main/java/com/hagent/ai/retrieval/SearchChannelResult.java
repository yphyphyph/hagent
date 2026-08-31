package com.hagent.ai.retrieval;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 单通道检索结果
 *
 * @author H
 */
@Data
@Builder
public class SearchChannelResult {

    /** 通道类型 */
    private SearchChannelType channelType;

    /** 通道名称 */
    private String channelName;

    /** 召回的块（按通道原始 score 降序） */
    private List<RetrievedChunk> chunks;

    /** 耗时毫秒 */
    private long latencyMs;
}
