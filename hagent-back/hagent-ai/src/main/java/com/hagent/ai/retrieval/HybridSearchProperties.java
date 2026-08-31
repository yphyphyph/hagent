package com.hagent.ai.retrieval;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 混合检索配置
 * 默认双路并行 + RRF k=20 等权融合，可一键退化为纯向量
 *
 * @author H
 */
@Data
@Component
@ConfigurationProperties(prefix = "hagent.hybrid")
public class HybridSearchProperties {

    /** 是否启用混合检索（false 时退化为纯向量） */
    private boolean enabled = true;

    /** RRF 平滑常数 k（经典 60，RAGent 20，候选池 20-40 时 20 更敏感） */
    private int rrfK = 20;

    /** 权重（等权 1.0/1.0，单通道时权重不生效直接透传） */
    private ChannelWeights weights = new ChannelWeights();

    /** 关键词通道 */
    private Keyword keyword = new Keyword();

    /** 单通道超时毫秒 */
    private long timeoutMs = 3000;

    @Data
    public static class ChannelWeights {
        private double vector = 1.0;
        private double keyword = 1.0;
    }

    @Data
    public static class Keyword {
        /** 每通道候选召回数 */
        private int topK = 20;
        /** 最小查询长度（短于此值直接跳过关键词通道） */
        private int minKeywordLength = 2;
    }
}
