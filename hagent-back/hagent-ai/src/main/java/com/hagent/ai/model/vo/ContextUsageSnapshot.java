package com.hagent.ai.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上下文用量快照
 * 后端现算的历史打开首值，与聊天 meta 首值同一口径：总数恒为四段之和；
 * rag 段首值保持工具 schema 占位，source 到达后由流内二次修正
 *
 * @author H
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "上下文用量快照")
public class ContextUsageSnapshot {

    /** 提示 token 总数（四段之和） */
    @Schema(description = "提示 token 总数")
    private int promptTokens;

    /** 上下文窗口（分母，可在设置页改） */
    @Schema(description = "上下文窗口")
    private int contextWindow;

    /** 使用百分比（四舍五入） */
    @Schema(description = "使用百分比")
    private int percent;

    /** 四段明细 */
    @Schema(description = "四段明细")
    private Breakdown breakdown;

    /**
     * 四段明细
     *
     * @author H
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "上下文用量四段明细")
    public static class Breakdown {

        /** 历史段（DB 历史原文求和） */
        @Schema(description = "历史段")
        private int history;

        /** 摘要段（会话摘要正文） */
        @Schema(description = "摘要段")
        private int summary;

        /** RAG 段（首值为工具 schema 占位） */
        @Schema(description = "RAG 段")
        private int rag;

        /** 系统段（默认提示词加自定义） */
        @Schema(description = "系统段")
        private int system;
    }

    /**
     * 组装快照（总数恒为四段之和）
     *
     * @param promptTokens 提示 token 总数
     * @param contextWindow 上下文窗口
     * @param percent 使用百分比
     * @param historyTokens 历史段
     * @param summaryTokens 摘要段
     * @param ragTokens RAG 段
     * @param systemTokens 系统段
     * @return 快照
     * @author H
     */
    public static ContextUsageSnapshot of(int promptTokens, int contextWindow, int percent,
                                          int historyTokens, int summaryTokens,
                                          int ragTokens, int systemTokens) {
        return new ContextUsageSnapshot(promptTokens, contextWindow, percent,
                new Breakdown(historyTokens, summaryTokens, ragTokens, systemTokens));
    }
}
