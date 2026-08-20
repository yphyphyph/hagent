package com.hagent.ai.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI待确认操作视图对象
 *
 * @author H
 */
@Data
@Schema(description = "AI待确认操作视图对象")
public class PendingActionVO {

    /** 主键ID */
    @Schema(description = "主键ID")
    private Long id;

    /** 会话ID */
    @Schema(description = "会话ID")
    private Long conversationId;

    /** 用户ID */
    @Schema(description = "用户ID")
    private Long userId;

    /** 操作类型：1-博客，2-订单，3-知识库 */
    @Schema(description = "操作类型：1-博客，2-订单，3-知识库")
    private Integer actionType;

    /** 目标表名 */
    @Schema(description = "目标表名")
    private String targetType;

    /** 目标记录ID */
    @Schema(description = "目标记录ID")
    private Long targetId;

    /** 载荷JSON数据 */
    @Schema(description = "载荷JSON数据")
    private String payloadJson;

    /** 用户确认预览文本 */
    @Schema(description = "用户确认预览文本")
    private String previewText;

    /** 状态：1-待确认，2-已确认，3-已取消，4-执行中，5-已执行 */
    @Schema(description = "状态：1-待确认，2-已确认，3-已取消，4-执行中，5-已执行")
    private Integer status;

    /** 状态描述 */
    @Schema(description = "状态描述")
    private String statusDesc;

    /** 过期时间 */
    @Schema(description = "过期时间")
    private LocalDateTime expireTime;

    /** 确认时间 */
    @Schema(description = "确认时间")
    private LocalDateTime confirmedAt;

    /** 执行时间 */
    @Schema(description = "执行时间")
    private LocalDateTime executedAt;

    /** 创建时间 */
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
