package com.hagent.ai.model.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 聊天附件列表校验注解
 * <p>
 * 列表级约束（按 type 分支）：image 图片 base64 可解码（失败 400 中文提示，不 500）、
 * 单张解码后不超过 5MB、总量不超过 8MB；document 文档已过知识库侧上传限制，仅校验 name 必填
 * 与 dataUrl 形态（http(s) URL 或空串）；数量上限由 @Size(max=4) 承担，
 * mime 必须 image/* 与 type 仅 image|document 由元素级注解承担。
 *
 * @author H
 */
@Documented
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = AttachmentsValidator.class)
public @interface ValidAttachments {

    /** 默认校验失败消息（具体失败原因由校验器动态给出） */
    String message() default "附件图片不合法";

    /** 校验分组 */
    Class<?>[] groups() default {};

    /** 校验载荷 */
    Class<? extends Payload>[] payload() default {};
}
