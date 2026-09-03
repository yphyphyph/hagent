package com.hagent.ai.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * 多级降级路由（无 Ollama，N 级）
 * 基于 {@link AiModelProvider#getChatModels()} 的有序列表（主 order 0 + 备用 1..N）逐级重试，
 * 不循环、每级仅一次，总重试 N 次，全部失败抛最后异常
 *
 * @author H
 */
@Slf4j
@Component
public class ModelRouter {

    private final AiModelProvider aiModelProvider;
    private final ModelHealthStore healthStore;
    private final LlmFirstPacketProbe firstPacketProbe;

    public ModelRouter(AiModelProvider aiModelProvider,
                       ModelHealthStore healthStore,
                       LlmFirstPacketProbe firstPacketProbe) {
        this.aiModelProvider = aiModelProvider;
        this.healthStore = healthStore;
        this.firstPacketProbe = firstPacketProbe;
    }

    /**
     * 获取有序对话模型列表（主 + 多级备用按 order 排序）
     *
     * @return 有序 ChatModel 列表，空回退时仅含主模型
     * @author H
     */
    public List<ChatModel> getOrderedModels() {
        return aiModelProvider.getChatModels();
    }

    /**
     * 解析模型名称，用于日志
     *
     * @param chatModel 对话模型
     * @return 模型名称
     * @author H
     */
    private String getModelName(ChatModel chatModel) {
        if (chatModel instanceof OpenAiChatModel openAiChatModel) {
            try {
                String name = openAiChatModel.getOptions().getModel();
                return name != null ? name : chatModel.getClass().getSimpleName();
            } catch (Exception e) {
                return chatModel.getClass().getSimpleName();
            }
        }
        return chatModel.getClass().getSimpleName();
    }

    /**
     * 多级降级调用（字符串便捷方法）
     * 按 order 0..N 顺序 for 循环重试，WARN 日志透出降级路径
     *
     * @param prompt 提示词
     * @return 模型返回文本
     * @author H
     */
    public String chatWithFallback(String prompt) {
        Prompt p = new Prompt(new UserMessage(prompt));
        ChatResponse response = callWithFallback(p);
        if (response.getResult() != null && response.getResult().getOutput() != null) {
            return response.getResult().getOutput().getText();
        }
        return "";
    }

    /**
     * 多级降级调用（Prompt 版本）
     *
     * @param prompt 提示词
     * @return ChatResponse
     * @author H
     */
    public ChatResponse callWithFallback(Prompt prompt) {
        List<ChatModel> chatModels = getOrderedModels();
        Exception lastException = null;
        for (int i = 0; i < chatModels.size(); i++) {
            ChatModel current = chatModels.get(i);
            String modelName = getModelName(current);
            // 健康过滤：OPEN 且未超时则跳过
            if (!healthStore.isAvailable(modelName)) {
                log.warn("fallback to level {}/{} {} health OPEN skip model={}", i, i + 1, modelName, modelName);
                // 构造占位异常以便全跳过时有 lastException
                if (lastException == null) {
                    lastException = new RuntimeException("model health OPEN skip model=" + modelName);
                }
                if (i == chatModels.size() - 1) {
                    break;
                }
                continue;
            }
            try {
                ChatResponse response = current.call(prompt);
                healthStore.recordSuccess(modelName);
                return response;
            } catch (Exception e) {
                lastException = e;
                healthStore.recordFailure(modelName);
                if (i == chatModels.size() - 1) {
                    break;
                }
                ChatModel next = chatModels.get(i + 1);
                String nextName = getModelName(next);
                log.warn("fallback to level {}/{} {} health OPEN={} cause={}", i, i + 1, nextName, !healthStore.isAvailable(nextName), e.toString());
            }
        }
        if (lastException instanceof RuntimeException re) {
            throw re;
        }
        throw new RuntimeException(lastException);
    }

    /**
     * 多级降级调用（Callable 版本，适配自定义动作）
     *
     * @param action 需在模型上下文中执行的动作（由调用方传入对当前模型的调用逻辑）
     * @return 动作返回值
     * @author H
     */
    public String resolveWithFallback(Callable<String> action) {
        List<ChatModel> chatModels = getOrderedModels();
        Exception lastException = null;
        for (int i = 0; i < chatModels.size(); i++) {
            try {
                return action.call();
            } catch (Exception e) {
                lastException = e;
                if (i == chatModels.size() - 1) {
                    break;
                }
                ChatModel next = chatModels.get(i + 1);
                log.warn("fallback to level {}/{} {}", i, i + 1, getModelName(next), e);
            }
        }
        if (lastException instanceof RuntimeException re) {
            throw re;
        }
        throw new RuntimeException(lastException);
    }

    /**
     * 多级降级流式调用
     * 首次订阅失败时逐级切换至下一模型，日志 WARN fallback to level
     *
     * @param prompt 提示词
     * @return 流式响应
     * @author H
     */
    public Flux<ChatResponse> streamWithFallback(Prompt prompt) {
        List<ChatModel> chatModels = getOrderedModels();
        return streamWithFallbackInternal(prompt, chatModels, 0);
    }

    /**
     * 判断是否为前端主动取消（CANCEL）
     * 特征：okhttp StreamResetException: CANCEL / stream was reset: CANCEL
     * / IOExceptionWrappingSequence hasNext 为 CANCEL / Reactor Cancellation 等
     *
     * @param e 异常
     * @return 是否为取消
     * @author H
     */
    static boolean isCancel(Throwable e) {
        if (e == null) {
            return false;
        }
        // 遍历 cause 链
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 10) {
            String cls = cur.getClass().getSimpleName();
            String msg = cur.getMessage();
            if (cls != null && (cls.toLowerCase().contains("cancel") || cls.contains("StreamResetException"))) {
                return true;
            }
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("cancel") || lower.contains("stream was reset") || lower.contains("ioexceptionwrappingsequence")) {
                    // 仅当包含 cancel 语义时判定
                    if (lower.contains("cancel")) {
                        return true;
                    }
                }
            }
            cur = cur.getCause();
            depth++;
        }
        // 兜底：顶层消息检查
        String topMsg = e.getMessage();
        if (topMsg != null && topMsg.toLowerCase().contains("cancel")) {
            return true;
        }
        return false;
    }

    /**
     * 判断是否可重试（模型侧 5xx/429/timeout）
     * 非取消且为超时或服务端错误时才允许降级重试
     *
     * @param e 异常
     * @return 是否可重试
     * @author H
     */
    static boolean isRetryable(Throwable e) {
        if (isCancel(e)) {
            return false;
        }
        if (e instanceof java.util.concurrent.TimeoutException) {
            return true;
        }
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 10) {
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                // 超时
                if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("first packet timeout")) {
                    return true;
                }
                // 429 限流
                if (lower.contains("429") || lower.contains("too many requests") || lower.contains("rate limit")) {
                    return true;
                }
                // 5xx 服务端错误
                if (lower.contains("500") || lower.contains("502") || lower.contains("503") || lower.contains("504")
                        || lower.contains("internal server error") || lower.contains("bad gateway")
                        || lower.contains("service unavailable") || lower.contains("gateway timeout")) {
                    return true;
                }
                // 通用服务端流失败（非 cancel）视为可重试，保证 Stream failed 仍可降级
                if (lower.contains("openaiioexception") || lower.contains("stream failed")) {
                    return true;
                }
            }
            // 检查 cause 的类名是否包含 Timeout
            if (cur.getClass().getSimpleName().toLowerCase().contains("timeout")) {
                return true;
            }
            cur = cur.getCause();
            depth++;
        }
        // 未识别的异常（如 4xx 客户端错误）默认不重试，遵循“仅 5xx/429/timeout 才重试”原则
        // 但为兼容未知服务端错误，若消息包含 5xx 特征已在上层返回，其余视为不可重试
        return false;
    }

    /**
     * 流式降级内部递归
     * 语义：CANCEL 为前端主动取消，直接 Flux.error 不再 WARN fallback，且不计 ModelHealthStore 失败；
     * 仅 5xx/429/timeout 才降级重试；LlmFirstPacketProbe 的 2s 超时已包装为 TimeoutException 计失败
     *
     * @param prompt     提示词
     * @param chatModels 有序模型
     * @param index      当前下标
     * @return Flux
     * @author H
     */
    private Flux<ChatResponse> streamWithFallbackInternal(Prompt prompt, List<ChatModel> chatModels, int index) {
        if (index >= chatModels.size()) {
            return Flux.error(new RuntimeException("all chat models failed"));
        }
        ChatModel current = chatModels.get(index);
        String modelName = getModelName(current);
        // 健康过滤：OPEN 跳过
        if (!healthStore.isAvailable(modelName)) {
            log.warn("fallback to level {}/{} {} health OPEN skip model={}", index, index + 1, modelName, modelName);
            if (index >= chatModels.size() - 1) {
                return Flux.error(new RuntimeException("all chat models failed - health OPEN " + modelName));
            }
            return streamWithFallbackInternal(prompt, chatModels, index + 1);
        }
        Flux<ChatResponse> raw = current.stream(prompt);
        // 首包 2s 超时探测
        Flux<ChatResponse> probed = firstPacketProbe.probeWithTimeout(raw, 2000);
        return probed
                .doOnNext(r -> healthStore.recordSuccess(modelName))
                .doOnComplete(() -> healthStore.recordSuccess(modelName))
                .onErrorResume(e -> {
                    // 前端主动取消：不计失败，不降级，直接透传由前端 MessageAggregator 正常取消
                    if (isCancel(e)) {
                        log.info("stream cancel detected, no fallback model={} cause={}", modelName, e.toString());
                        return Flux.error(e);
                    }
                    // 仅 5xx/429/timeout 才重试并计失败；其他非重试异常直接透传
                    if (!isRetryable(e)) {
                        log.info("stream non-retryable error, no fallback model={} cause={}", modelName, e.toString());
                        return Flux.error(e);
                    }
                    healthStore.recordFailure(modelName);
                    if (index >= chatModels.size() - 1) {
                        return Flux.error(e);
                    }
                    ChatModel next = chatModels.get(index + 1);
                    String nextName = getModelName(next);
                    log.warn("fallback to level {}/{} {} health OPEN={} cause={}", index, index + 1, nextName, !healthStore.isAvailable(nextName), e.toString());
                    return streamWithFallbackInternal(prompt, chatModels, index + 1);
                });
    }

    /**
     * 按下标重试获取下一模型，失败时抛最后异常
     * 供外部在捕获异常后调用以判定是否还有下一级
     *
     * @param attempt 当前尝试下标
     * @param ex      当前异常
     * @return 下一模型
     * @author H
     */
    public ChatModel nextOrFail(int attempt, Exception ex) {
        List<ChatModel> chatModels = getOrderedModels();
        int nextIndex = attempt + 1;
        if (nextIndex >= chatModels.size()) {
            if (ex instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(ex);
        }
        ChatModel next = chatModels.get(nextIndex);
        log.warn("fallback to level {}/{} {}", attempt, nextIndex, getModelName(next), ex);
        return next;
    }
}
