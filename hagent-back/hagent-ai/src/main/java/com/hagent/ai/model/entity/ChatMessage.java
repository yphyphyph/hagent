package com.hagent.ai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 聊天消息实体，对应表 t_chat_message
 *
 * @author H
 */
@Data
@TableName("chat_message")
public class ChatMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 消息ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话ID */
    private Long conversationId;

    /** 消息角色：1-用户，2-助手，3-系统，4-工具 */
    private Integer role;

    /** 消息内容 */
    private String content;

    /** 消息类型：TEXT/MARKDOWN/TOOL_CALL */
    private String messageType;

    /** 工具名称（角色为工具时） */
    private String toolName;

    /** 工具调用载荷JSON */
    private String toolPayload;

    /** 思考过程内容（reasoning/thinking，仅助手消息） */
    @TableField("thinking_content")
    @Schema(description = "思考过程内容")
    private String thinkingContent;

    /** 来源引用(JSON数组) */
    @TableField("sources")
    @Schema(description = "来源引用(JSON数组)")
    private String sources;

    /** 链路追踪ID（复用 system_log.trace_id 的 MDC 生成，轻量 Trace 预留，不强制建库） */
    @TableField("trace_id")
    @Schema(description = "链路追踪ID")
    private String traceId;

    /** 消息Token数量 */
    private Integer tokenCount;

    /** 状态：1-待处理，2-流式传输中，3-已完成，4-失败 */
    private Integer status;

    /** 错误信息 */
    private String errorMessage;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 创建人 */
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 更新人 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
}
