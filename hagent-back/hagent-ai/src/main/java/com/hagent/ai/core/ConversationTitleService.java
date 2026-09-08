package com.hagent.ai.core;

import com.hagent.ai.service.ConversationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;

/**
 * 会话标题异步生成服务
 * 首条消息默认以原文为标题（不截断），首轮回答完成后异步用 LLM 生成更贴切的标题；
 * 生成失败时静默保留占位标题，不影响对话主链路
 *
 * @author H
 */
@Slf4j
@Service
public class ConversationTitleService {

    /** 默认会话标题（与 ChatServiceImpl/ConversationServiceImpl 的占位一致） */
    private static final String DEFAULT_TITLE = "新会话";

    /** 标题生成系统提示词：要求输出纯标题本身 */
    private static final String TITLE_SYSTEM_PROMPT =
            "你是标题生成器。根据用户的第一条消息，生成一个不超过12个字的中文会话标题。"
                    + "只输出标题本身：不要引号、不要句号等结尾标点、不要任何解释或前缀。";

    /** 标题生成专用线程池：单线程即可（生成频率低），CallerRunsPolicy 兜底防任务丢失 */
    private final ThreadPoolExecutor titleExecutor = new ThreadPoolExecutor(
            1, 1, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(64),
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(1);

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "hagent-title-" + counter.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    /** 生成中去重：避免同一会话并发重复生成 */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    private final AiModelProvider aiModelProvider;
    private final ConversationService conversationService;

    public ConversationTitleService(AiModelProvider aiModelProvider,
                                    ConversationService conversationService) {
        this.aiModelProvider = aiModelProvider;
        this.conversationService = conversationService;
    }

    @PreDestroy
    public void shutdown() {
        titleExecutor.shutdown();
    }

    /**
     * 按需异步生成会话标题
     * 仅当当前标题仍为默认占位"新会话"时触发；同一会话在生成中不重复提交
     *
     * @param conversationId 会话ID
     * @param currentTitle   当前标题
     * @param firstQuestion  首条用户消息（标题语料）
     */
    public void generateIfNeeded(Long conversationId, String currentTitle, String firstQuestion) {
        if (conversationId == null || firstQuestion == null || firstQuestion.isBlank()) {
            return;
        }
        if (currentTitle != null && !DEFAULT_TITLE.equals(currentTitle)) {
            return;
        }
        if (!inFlight.add(conversationId)) {
            return;
        }
        try {
            titleExecutor.execute(() -> {
                try {
                    String title = generateTitle(firstQuestion);
                    if (title != null && !title.isBlank()) {
                        conversationService.updateTitle(conversationId, title);
                        log.info("会话标题已生成: conversationId={}, title={}", conversationId, title);
                    }
                } catch (Exception e) {
                    // 静默失败：保留占位标题，不影响对话主链路
                    log.warn("会话标题生成失败，保留占位标题: conversationId={}, error={}", conversationId, e.getMessage());
                } finally {
                    inFlight.remove(conversationId);
                }
            });
        } catch (Exception e) {
            inFlight.remove(conversationId);
            log.warn("提交标题生成任务失败: conversationId={}, error={}", conversationId, e.getMessage());
        }
    }

    /**
     * 调用 LLM 生成标题并清洗输出
     *
     * @param question 首条用户消息
     * @return 清洗后的标题；生成失败返回 null
     */
    private String generateTitle(String question) {
        ChatModel chatModel = aiModelProvider.getChatModel();
        if (chatModel == null) {
            return null;
        }
        // 截断过长问题，控制生成成本
        String clipped = question.length() > 200 ? question.substring(0, 200) : question;
        var response = chatModel.call(new Prompt(List.of(
                new SystemMessage(TITLE_SYSTEM_PROMPT),
                new UserMessage(clipped))));
        String text = response.getResult() != null && response.getResult().getOutput() != null
                ? response.getResult().getOutput().getText()
                : null;
        return cleanTitle(text);
    }

    /**
     * 清洗模型输出：去除引号/换行/markdown 标记与"标题："类前缀（不做长度截断）
     *
     * @param raw 模型原始输出
     * @return 清洗后的标题；不合法返回 null
     */
    private String cleanTitle(String raw) {
        if (raw == null) {
            return null;
        }
        String title = raw.trim()
                .replaceAll("^[#*\\s`\"'“”‘’]+", "")
                .replaceAll("[#*`\"'“”‘’\\s]+$", "")
                .replaceAll("^(标题|会话标题)[:：]\\s*", "")
                .replaceAll("[\\r\\n].*$", "")
                .replaceAll("。$", "")
                .trim();
        if (title.isEmpty() || DEFAULT_TITLE.equals(title)) {
            return null;
        }
        return title;
    }
}
