package com.hagent.ai.model.validation;

import com.hagent.ai.model.dto.ChatRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * 「重新生成/编辑重发」跨字段校验器
 * <p>
 * 规则（失败均返回 400 中文提示，由全局异常处理器统一输出）：
 * 1. regenerate 与 editMessageId 互斥，同时传 → 「不支持同时重新生成与编辑」
 * 2. 任一操作出现时必须携带 conversationId → 「该操作需要已保存的会话」
 *
 * @author H
 */
public class RegenerateOrEditValidator implements ConstraintValidator<ValidRegenerateOrEdit, ChatRequest> {

    /**
     * 校验重新生成与编辑重发字段的互斥性与会话前置条件
     *
     * @param request 聊天请求
     * @param context 校验上下文
     * @return 校验通过返回 true，否则 false（消息写入 context）
     * @author H
     */
    @Override
    public boolean isValid(ChatRequest request, ConstraintValidatorContext context) {
        // 请求体为空交由元素级注解（@NotBlank 等）处理，此处不重复校验
        if (request == null) {
            return true;
        }
        boolean regenerate = Boolean.TRUE.equals(request.getRegenerate());
        boolean edit = request.getEditMessageId() != null;
        // 未使用任一操作，直接放行
        if (!regenerate && !edit) {
            return true;
        }
        // 两者互斥：同时传视为非法请求
        if (regenerate && edit) {
            return fail(context, "不支持同时重新生成与编辑");
        }
        // 重新生成/编辑重发必须基于已保存的会话（访客/无会话拒绝）
        if (request.getConversationId() == null) {
            return fail(context, "该操作需要已保存的会话");
        }
        return true;
    }

    /**
     * 输出指定中文错误消息并标记校验失败
     *
     * @param context 校验上下文
     * @param message 错误消息
     * @return 固定返回 false 表示校验失败
     * @author H
     */
    private boolean fail(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
