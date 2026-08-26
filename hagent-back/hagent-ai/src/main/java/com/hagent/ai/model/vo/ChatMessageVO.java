package com.hagent.ai.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息视图对象
 */
@Data
@Schema(description = "聊天消息视图对象")
public class ChatMessageVO {

    /** 消息ID */
    @Schema(description = "消息ID")
    private Long id;

    /** 会话ID */
    @Schema(description = "会话ID")
    private Long conversationId;

    /** 消息角色：1-用户，2-助手，3-系统，4-工具 */
    @Schema(description = "消息角色：1-用户，2-助手，3-系统，4-工具")
    private Integer role;

    /** 角色描述 */
    @Schema(description = "角色描述：1-用户，2-助手，3-系统，4-工具")
    private String roleDesc;

    /** 消息内容 */
    @Schema(description = "消息内容")
    private String content;

    /** 消息类型：TEXT/MARKDOWN/TOOL_CALL */
    @Schema(description = "消息类型：TEXT/MARKDOWN/TOOL_CALL")
    private String messageType;

    /** 工具名称（角色为工具时） */
    @Schema(description = "工具名称（角色为工具时）")
    private String toolName;

    /** 工具调用载荷 */
    @Schema(description = "工具调用载荷")
    private String toolPayload;

    /** 思考过程内容（reasoning/thinking，仅助手消息历史回显） */
    @Schema(description = "思考过程内容")
    private String thinkingContent;

    /** Token数量 */
    @Schema(description = "Token数量")
    private Integer tokenCount;

    /** 状态：1-待处理，2-流式传输中，3-已完成，4-失败 */
    @Schema(description = "状态：1-待处理，2-流式传输中，3-已完成，4-失败")
    private Integer status;

    /** 错误信息 */
    @Schema(description = "错误信息")
    private String errorMessage;

    /** 创建时间 */
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
