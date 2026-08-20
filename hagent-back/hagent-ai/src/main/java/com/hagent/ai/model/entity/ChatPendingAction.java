package com.hagent.ai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.hagent.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * AI待确认操作实体
 * 存储需要用户确认后才能执行的待处理操作
 * 对应表 t_chat_pending_action
 *
 * @author H
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_pending_action")
public class ChatPendingAction extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 会话ID */
    private Long conversationId;

    /** 用户ID */
    private Long userId;

    /** 操作类型：1-博客，2-订单，3-知识库 */
    private Integer actionType;

    /** 目标表名 */
    private String targetType;

    /** 目标记录ID */
    private Long targetId;

    /** 载荷JSON数据 */
    private String payloadJson;

    /** 用户确认预览文本 */
    private String previewText;

    /** 状态：1-待处理，2-已确认，3-已取消，4-执行中，5-已执行 */
    private Integer status;

    /** 过期时间 */
    private LocalDateTime expireTime;

    /** 确认时间 */
    private LocalDateTime confirmedAt;

    /** 执行时间 */
    private LocalDateTime executedAt;
}
