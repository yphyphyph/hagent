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

    public ModelRouter(AiModelProvider aiModelProvider) {
        this.aiModelProvider = aiModelProvider;
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
            try {
                return current.call(prompt);
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
     * 流式降级内部递归
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
        return current.stream(prompt)
                .onErrorResume(e -> {
                    if (index >= chatModels.size() - 1) {
                        return Flux.error(e);
                    }
                    ChatModel next = chatModels.get(index + 1);
                    log.warn("fallback to level {}/{} {}", index, index + 1, getModelName(next), e);
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
