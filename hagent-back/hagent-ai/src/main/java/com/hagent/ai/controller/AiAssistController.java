package com.hagent.ai.controller;

import com.hagent.ai.core.AiModelProvider;
import com.hagent.common.Result;
import com.hagent.common.annotation.RateLimit;
import com.hagent.common.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * AI 写作辅助控制器
 * 为文章编辑器提供同步的 AI 能力：生成摘要 / 标题 / SEO 建议 / 润色
 * 直接复用 {@link AiModelProvider} 的 ChatModel 做一次性 call，不写入会话历史
 *
 * @author H
 */
@Slf4j
@Tag(name = "AI写作辅助", description = "为文章编辑器提供摘要、标题、SEO建议与润色等同步AI能力接口")
@RestController
@RequestMapping("/admin/ai/assist")
@RequiredArgsConstructor
public class AiAssistController {

    private final AiModelProvider aiModelProvider;

    /** 送入模型的正文最大长度，超出截断，防止上下文超限 */
    private static final int MAX_CONTENT_LENGTH = 6000;

    /**
     * AI 写作辅助
     *
     * @param request 请求体（action / content / title）
     * @return 包含 result 字段的生成结果
     */
    @PostMapping
    @Operation(summary = "AI写作辅助（摘要/标题/SEO/润色）", description = "根据 action 对文章内容生成摘要/标题/SEO建议/润色结果，一次性同步返回")
    @ApiResponse(responseCode = "200", description = "生成成功")
    @RateLimit(window = 60, limit = 15, message = "AI 请求过于频繁，请稍后重试")
    public Result<Map<String, String>> assist(@RequestBody AssistRequest request) {
        if (request == null || !StringUtils.hasText(request.getContent())) {
            throw new BusinessException("内容不能为空");
        }
        String action = StringUtils.hasText(request.getAction()) ? request.getAction() : "summary";
        String content = request.getContent();
        if (content.length() > MAX_CONTENT_LENGTH) {
            content = content.substring(0, MAX_CONTENT_LENGTH);
        }

        String prompt = buildPrompt(action, content);
        try {
            ChatModel model = aiModelProvider.getChatModel();
            ChatResponse response = model.call(new Prompt(prompt));
            String reply = response != null && response.getResult() != null && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText() : "";
            Map<String, String> data = new HashMap<>();
            data.put("result", reply == null ? "" : reply.trim());
            log.info("AI写作辅助成功: action={}, replyLen={}", action, reply == null ? 0 : reply.length());
            return Result.success(data);
        } catch (Exception e) {
            log.error("AI写作辅助失败: action={}", action, e);
            throw new BusinessException("AI 生成失败，请稍后重试");
        }
    }

    /**
     * 按动作类型构建提示词
     *
     * @param action  动作：title/seo/polish/summary
     * @param content 文章正文
     * @return 提示词
     */
    private String buildPrompt(String action, String content) {
        return switch (action) {
            case "title" -> "请为下面的文章生成一个吸引人的中文标题，直接输出标题本身，不要加引号或多余说明，不超过 30 字：\n\n" + content;
            case "seo" -> "请针对下面的文章给出 SEO 优化建议，包含：推荐标题、关键词（5 个左右）、meta 描述（120 字内）。用简洁中文分点输出：\n\n" + content;
            case "polish" -> "请对下面的文章进行润色，使表达更流畅专业，保持原意与 Markdown 格式，直接输出润色后的正文：\n\n" + content;
            default -> "请为下面的文章生成一段简洁的中文摘要，100 字以内，直接输出摘要本身，不要加任何前缀说明：\n\n" + content;
        };
    }

    /**
     * AI 写作辅助请求体
     */
    @Data
    public static class AssistRequest {
        /** 动作类型：summary(默认)/title/seo/polish */
        private String action;
        /** 文章正文 */
        private String content;
        /** 文章标题（可选） */
        private String title;
    }
}
