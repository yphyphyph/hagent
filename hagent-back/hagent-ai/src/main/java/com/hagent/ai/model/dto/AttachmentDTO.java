package com.hagent.ai.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 聊天附件DTO
 * <p>
 * 附件契约（与前端锁死）：
 * ① image 图片附件：{type:"image", dataUrl, name?}，dataUrl 支持 base64（data:image/*;base64,...）
 * 与 http(s) URL 两种形态二选一（前端先传 OSS 拿 URL 再发送）；
 * ② document 文档附件：{type:"document", name, url?}，文档已在上传时入知识库并向量化，
 * 消息侧仅留痕，dataUrl 字段承载知识库上传回传的 http(s) 文档 URL（可空，字段名保持 dataUrl 不变）；
 * 数量/大小/解码/形态校验由 {@link com.hagent.ai.model.validation.ValidAttachments} 列表级约束按 type 分支统一把关。
 *
 * @author H
 */
@Data
@Schema(description = "聊天附件")
public class AttachmentDTO {

    /** 附件类型：image（图片，进多模态 Media） */
    public static final String TYPE_IMAGE = "image";

    /** 附件类型：document（文档，走知识库 RAG 通道，消息侧仅留痕） */
    public static final String TYPE_DOCUMENT = "document";

    /** 附件类型（image=图片，document=文档，其他类型 400 拒绝） */
    @NotBlank(message = "附件类型不能为空")
    @Pattern(regexp = "image|document", message = "附件类型仅支持 image 或 document")
    @Schema(description = "附件类型（image=图片，document=文档）", example = "image")
    private String type;

    /**
     * 附件数据（字段名保持 dataUrl 不变，按 type 形态二选一）：
     * image：① data:image/*;base64,...（base64 形态，mime 必须 image/*）；
     * ② http(s):// 开头的图片 URL（URL 形态，仅校验格式，不做服务端下载探测）；
     * document：http(s):// 文档 URL（知识库上传回传 fileUrl）或空串（仅留痕，无外链）；
     * 形态合法性由 ValidAttachments 列表级校验按 type 分支把关
     */
    @Schema(description = "附件数据（image 为 data:image/*;base64,... 或 http(s) 图片 URL；document 为 http(s) 文档 URL 或空串）",
            example = "data:image/png;base64,iVBORw0KGgo=")
    private String dataUrl;

    /** 附件名称（image 可选；document 必填，落库 [文档：name](url) 标记依赖该字段） */
    @Size(max = 200, message = "附件名称不能超过200个字符")
    @Schema(description = "附件名称（image 可选，document 必填）", example = "截图.png")
    private String name;

    /**
     * 知识库文件ID（可空，仅 type=document 时有意义）：前端上传文档入知识库后回传的 knowledge_file 主键。
     * 契约：document 带 fileId 时落库/组消息为 "[文档：name|fileId](url)" 新格式标记，
     * 模型侧 searchKnowledge 按 fileId 参数精确锁定检索（仅向量通道），不再依赖文件名语义匹配；
     * 历史/旧前端无该字段时为 null，走旧格式 "[文档：name](url)"，两者前端解析均合法
     */
    @Schema(description = "知识库文件ID（仅 document 附件有意义，可空；检索时用于精确锁定知识库文件）",
            example = "5")
    private Long fileId;
}
