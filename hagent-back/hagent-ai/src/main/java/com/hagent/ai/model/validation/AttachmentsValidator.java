package com.hagent.ai.model.validation;

import com.hagent.ai.model.dto.AttachmentDTO;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Base64;
import java.util.List;

/**
 * 聊天附件列表校验器（按 type 分支校验）
 * <p>
 * image 图片：base64 必须可解码（解码失败按 400 中文提示返回，不抛 500）、
 * 单张解码后不超过 5MB、全部图片解码总量不超过 8MB；http(s) URL 形态不做服务端下载探测（保持轻），
 * 不参与 base64 解码与大小上限校验；
 * document 文档：已过知识库侧上传限制（类型/大小在知识库上传接口把关），消息侧仅校验 name 必填
 * 与 dataUrl 形态（http(s) URL 或空串），跳过大小/解码检查；
 * type 为空或未知类型交由元素级 @NotBlank/@Pattern 拒绝，此处跳过避免重复报错。
 *
 * @author H
 */
public class AttachmentsValidator implements ConstraintValidator<ValidAttachments, List<AttachmentDTO>> {

    /** 单张图片解码后大小上限（5MB） */
    private static final long MAX_SINGLE_BYTES = 5L * 1024 * 1024;

    /** 全部附件解码后总量上限（8MB） */
    private static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024;

    /** data URL 的 base64 数据段标记 */
    private static final String BASE64_MARKER = ";base64,";

    /** 图片 data URL 前缀 */
    private static final String IMAGE_DATA_URL_PREFIX = "data:image/";

    /**
     * 校验附件列表：按 type 分支校验（image 走解码/大小口径，document 走留痕口径）
     *
     * @param attachments 附件列表（null 或空视为合法，附件为可选项）
     * @param context     校验上下文
     * @return 合法返回 true；非法返回 false 并挂载中文违规消息
     * @author H
     */
    @Override
    public boolean isValid(List<AttachmentDTO> attachments, ConstraintValidatorContext context) {
        if (attachments == null || attachments.isEmpty()) {
            return true;
        }
        boolean valid = true;
        long totalBytes = 0;
        for (int i = 0; i < attachments.size(); i++) {
            AttachmentDTO attachment = attachments.get(i);
            if (attachment == null) {
                continue;
            }
            String type = attachment.getType();
            // type 为空或未知类型：元素级 @NotBlank/@Pattern 已报错，此处跳过避免重复报错
            if (type == null || type.isBlank()) {
                continue;
            }
            if (AttachmentDTO.TYPE_DOCUMENT.equals(type)) {
                // 文档：已过知识库侧上传限制，仅校验 name 必填与 dataUrl 形态，不占图片大小口径
                if (!validateDocumentAttachment(attachment, i, context)) {
                    valid = false;
                }
                continue;
            }
            if (!AttachmentDTO.TYPE_IMAGE.equals(type)) {
                // 未知类型由元素级 @Pattern 拒绝，此处不重复报错
                continue;
            }
            // 图片：非空 + 形态（base64 image 或 http(s) URL 二选一）+ 解码 + 单张/总量大小校验（既有口径不变）
            String dataUrl = attachment.getDataUrl();
            if (dataUrl == null || dataUrl.isBlank()) {
                addViolation(context, "附件图片数据不能为空");
                valid = false;
                continue;
            }
            // http(s) URL 形态：不下载探测直接放行，不参与 base64 解码与大小上限
            if (dataUrl.startsWith("http://") || dataUrl.startsWith("https://")) {
                continue;
            }
            // base64 形态：必须 data:image/*;base64 前缀（原元素级 @Pattern 口径，消息保持一致）
            int markerIdx = dataUrl.indexOf(BASE64_MARKER);
            if (!dataUrl.startsWith(IMAGE_DATA_URL_PREFIX) || markerIdx < 0) {
                addViolation(context, "附件图片必须为 data:image/*;base64 格式或 http(s) 图片链接");
                valid = false;
                continue;
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(dataUrl.substring(markerIdx + BASE64_MARKER.length()));
            } catch (IllegalArgumentException e) {
                // base64 解码失败：以 400 中文提示拒绝，不向上抛异常导致 500
                addViolation(context, "第 " + (i + 1) + " 张附件图片数据解码失败，请提供合法的 base64 内容");
                valid = false;
                continue;
            }
            if (bytes.length > MAX_SINGLE_BYTES) {
                addViolation(context, "第 " + (i + 1) + " 张附件图片超过 5MB 上限");
                valid = false;
            }
            totalBytes += bytes.length;
        }
        if (totalBytes > MAX_TOTAL_BYTES) {
            addViolation(context, "附件图片总量不能超过 8MB");
            valid = false;
        }
        return valid;
    }

    /**
     * 校验文档附件：name 必填（落库 [文档：name](url) 标记依赖）、dataUrl 允许 http(s) URL 或空串；
     * 大小/解码检查跳过——文档已过知识库侧上传限制（类型/大小在上传接口把关），消息侧仅留痕
     *
     * @param attachment 文档附件
     * @param index      序号（从 0 计，违规消息展示从 1 计）
     * @param context    校验上下文
     * @return 合法返回 true；非法返回 false 并挂载中文违规消息
     * @author H
     */
    private boolean validateDocumentAttachment(AttachmentDTO attachment, int index, ConstraintValidatorContext context) {
        boolean valid = true;
        // name 必填：落库标记 [文档：name](url) 与前端历史解析契约依赖该字段
        if (attachment.getName() == null || attachment.getName().isBlank()) {
            addViolation(context, "第 " + (index + 1) + " 个文档附件名称不能为空");
            valid = false;
        }
        String dataUrl = attachment.getDataUrl();
        // 空串/null 放行：文档仅留痕时允许无外链
        if (dataUrl == null || dataUrl.isBlank()) {
            return valid;
        }
        // 仅允许 http(s) URL（知识库上传回传的 fileUrl）；base64 内联与其他格式一律拒绝
        if (!dataUrl.startsWith("http://") && !dataUrl.startsWith("https://")) {
            addViolation(context, "第 " + (index + 1) + " 个文档附件仅支持 http(s) 链接或留空，不支持 base64 内联数据");
            valid = false;
        }
        return valid;
    }

    /**
     * 挂载自定义中文违规消息（禁用默认消息，避免与动态消息重复）
     *
     * @param context 校验上下文
     * @param message 中文违规消息
     * @author H
     */
    private void addViolation(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
    }
}
