package com.hagent.ai.model.dto;

import com.hagent.ai.model.validation.ValidAttachments;
import com.hagent.ai.model.validation.ValidRegenerateOrEdit;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 聊天请求DTO
 */
@Data
@ValidRegenerateOrEdit
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

    /** 聊天附件（可选，最多 4 个：image 图片单张解码后不超过 5MB、总量不超过 8MB；document 文档已过知识库侧上传限制） */
    @Size(max = 4, message = "附件图片最多 4 张")
    @ValidAttachments
    @Schema(description = "聊天附件列表（可选，最多 4 个，支持 image 图片与 document 文档类型）")
    private List<@Valid AttachmentDTO> attachments;

    /** 重新生成标记：true 时忽略 message 文本，重用会话最后一条用户提问并软删其后的助手回复（与 editMessageId 互斥） */
    @Schema(description = "是否重新生成最后一条回答（true=重用最后一条用户提问并软删其后的助手回复，忽略 message 文本；与 editMessageId 互斥）")
    private Boolean regenerate;

    /** 编辑重发的目标用户消息ID：软删该消息及其后所有消息，以 message 为新文本重新提问（与 regenerate 互斥） */
    @Schema(description = "编辑重发的目标用户消息ID（软删该消息及其后所有消息，message 为新文本重新提问；与 regenerate 互斥）")
    private Long editMessageId;
}
