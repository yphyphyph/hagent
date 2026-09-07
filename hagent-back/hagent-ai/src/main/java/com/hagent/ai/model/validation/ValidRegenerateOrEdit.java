package com.hagent.ai.model.validation;

import com.hagent.ai.model.dto.ChatRequest;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 聊天请求「重新生成/编辑重发」类级校验注解
 * <p>
 * 跨字段约束：regenerate 与 editMessageId 互斥（同时传 400 中文提示）；
 * 任一操作出现时必须携带已保存的 conversationId（访客/无会话 400 中文提示）。
 *
 * @author H
 */
@Documented
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = RegenerateOrEditValidator.class)
public @interface ValidRegenerateOrEdit {

    /** 默认校验失败消息（具体失败原因由校验器动态给出） */
    String message() default "请求参数不合法";

    /** 校验分组 */
    Class<?>[] groups() default {};

    /** 校验载荷 */
    Class<? extends Payload>[] payload() default {};
}
