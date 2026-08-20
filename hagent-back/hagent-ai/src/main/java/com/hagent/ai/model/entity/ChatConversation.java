package com.hagent.ai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.hagent.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 聊天会话实体，对应表 t_chat_conversation
 *
 * @author H
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_conversation")
public class ChatConversation extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 用户ID */
    private Long userId;

    /** 会话标题 */
    private String title;

    /** 聊天场景：1-通用，2-订单，3-知识库 */
    private Integer scene;

    /** 自定义系统提示词 */
    private String systemPrompt;

    /** RAG文件ID列表 */
    private String ragFileIds;

    /** 模型名称 */
    private String modelName;

    /** 温度参数 */
    private Double temperature;

    /** 每次响应最大Token数 */
    private Integer maxTokens;

    /** 已使用Token总数 */
    private Long totalTokens;

    /** 消息数量 */
    private Integer messageCount;

    /** 最后消息时间 */
    private LocalDateTime lastMessageAt;

    /** 状态：1-活跃，2-已归档 */
    private Integer status;
}
