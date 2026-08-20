package com.hagent.ai.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话视图对象
 */
@Data
@Schema(description = "会话视图对象")
public class ConversationVO {

    /** 会话ID */
    @Schema(description = "会话ID")
    private Long id;

    /** 用户ID */
    @Schema(description = "用户ID")
    private Long userId;

    /** 会话标题 */
    @Schema(description = "会话标题")
    private String title;

    /** 聊天场景：1-通用，2-订单，3-知识库 */
    @Schema(description = "聊天场景：1-通用，2-订单，3-知识库")
    private Integer scene;

    /** 场景描述 */
    @Schema(description = "场景描述")
    private String sceneDesc;

    /** 系统提示词 */
    @Schema(description = "系统提示词")
    private String systemPrompt;

    /** RAG文件ID列表 */
    @Schema(description = "RAG文件ID列表")
    private String ragFileIds;

    /** 模型名称 */
    @Schema(description = "模型名称")
    private String modelName;

    /** 温度参数 */
    @Schema(description = "温度参数")
    private Double temperature;

    /** 每次响应最大Token数 */
    @Schema(description = "每次响应最大Token数")
    private Integer maxTokens;

    /** 已使用Token总数 */
    @Schema(description = "已使用Token总数")
    private Long totalTokens;

    /** 消息数量 */
    @Schema(description = "消息数量")
    private Integer messageCount;

    /** 最后消息时间 */
    @Schema(description = "最后消息时间")
    private LocalDateTime lastMessageAt;

    /** 状态：1-活跃，2-已归档 */
    @Schema(description = "状态：1-活跃，2-已归档")
    private Integer status;

    /** 创建时间 */
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
