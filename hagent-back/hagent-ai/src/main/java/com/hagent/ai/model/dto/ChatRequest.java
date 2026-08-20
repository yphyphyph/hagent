package com.hagent.ai.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 聊天请求DTO
 */
@Data
@Schema(description = "聊天请求")
public class ChatRequest {

    /** 会话ID（为空则创建新会话） */
    @Schema(description = "会话ID（为空则创建新会话）")
    private Long conversationId;

    /** 用户消息内容 */
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 10000, message = "消息长度不能超过10000个字符")
    @Schema(description = "用户消息内容")
    private String message;

    /** 聊天场景：1-通用，2-订单，3-知识库 */
    @Schema(description = "聊天场景：1-通用，2-订单，3-知识库")
    private Integer scene;
}
