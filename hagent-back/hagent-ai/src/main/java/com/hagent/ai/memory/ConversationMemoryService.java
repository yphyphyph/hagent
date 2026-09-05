package com.hagent.ai.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hagent.ai.config.AiConfig;
import com.hagent.ai.core.ContextTokenEstimator;
import com.hagent.ai.core.ModelRouter;
import com.hagent.ai.mapper.ChatConversationMapper;
import com.hagent.ai.mapper.ChatMessageMapper;
import com.hagent.ai.model.entity.ChatConversation;
import com.hagent.ai.model.entity.ChatMessage;
import com.hagent.ai.model.enums.MessageRoleEnum;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话摘要压缩服务（单机版）
 * 长会话触发增量式滚动摘要：原文 transcript 进模型、旧摘要仅字段级 merge，
 * 覆盖写回 chat_conversation.summary_text + summary_up_to_message_id。
 * 失败仅日志不抛错，由调用方回退纯滑窗；并发由单机锁互斥。
 *
 * @author H
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationMemoryService {

    /** token 占比强制压缩阈值，达到即无视轮数压一次 */
    private static final double FORCE_COMPRESS_PERCENT = 80.0;

    /** 摘要装饰前缀：提醒模型摘要仅供背景参考 */
    private static final String SUMMARY_PREFIX = "【历史摘要，仅供背景参考，以本轮对话为准】\n";

    /** 单机锁键前缀：key = 前缀 + 会话ID */
    private static final String LOCK_KEY_PREFIX = "memory-summary:";

    /** 摘要系统提示词：结构化五段 + 去重归并约束 */
    private static final String SUMMARY_SYSTEM_PROMPT = "你是会话摘要器。把给定的历史对话压缩为结构化摘要，"
            + "只输出以下五段（每段无内容则写“无”），不要输出其他文字：\n"
            + "Objective（用户目标）\nDecisions（已确认的决策/结论）\n"
            + "Rejected（已明确否决的方案）\nArtifacts（已产出的关键产物/代码/配置）\n"
            + "Unresolved（未解决的问题与下一步）";

    private final AiConfig aiConfig;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatConversationMapper chatConversationMapper;
    private final ModelRouter modelRouter;
    private final ThreadPoolTaskExecutor memorySummaryExecutor;

    /** 单机会话锁：对标 keycloak computeIfAbsent 范式 */
    private final ConcurrentHashMap<String, ReentrantLock> lockMap = new ConcurrentHashMap<>();

    /**
     * 摘要异步线程池配置（本类内声明，风格对标 knowledge 模块 vectorizeExecutor）
     *
     * @author H
     */
    @Configuration
    public static class MemorySummaryExecutorConfig {

        /**
         * 摘要压缩专用线程池：低频后台任务，核心 2 线程、最大 4 线程、队列 50，
         * 满载时由调用线程兜底执行，避免任务丢失。
         *
         * @return 线程池执行器
         */
        @Bean(name = "memorySummaryExecutor")
        public ThreadPoolTaskExecutor memorySummaryExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(2);
            executor.setMaxPoolSize(4);
            executor.setQueueCapacity(50);
            executor.setThreadNamePrefix("memory-summary-");
            executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
            executor.setWaitForTasksToCompleteOnShutdown(true);
            executor.setAwaitTerminationSeconds(30);
            executor.initialize();
            return executor;
        }
    }

    /**
     * 按需触发会话摘要压缩（同步，调用方决定是否异步）。
     * 门禁：总 user 消息数达到 triggerTurns 首压，之后每新增 intervalTurns*2 条 user 消息再压；
     * 水位内双重检查 + 单机锁保证幂等（幂等键 conversation_id + summary_up_to_message_id）。
     *
     * @param conversationId 会话ID
     * @param userId         用户ID（归属校验）
     */
    public void compressIfNeeded(Long conversationId, Long userId) {
        if (conversationId == null) {
            return;
        }
        AiConfig.MemorySummary config = aiConfig.getMemorySummary();
        if (config == null || !config.isEnabled()) {
            return;
        }
        try {
            ChatConversation conversation = chatConversationMapper.selectById(conversationId);
            if (conversation == null) {
                return;
            }
            if (userId != null && !userId.equals(conversation.getUserId())) {
                log.warn("会话归属不匹配，跳过摘要压缩: conversationId={}, userId={}", conversationId, userId);
                return;
            }
            // token 安全阀：占比达阈值即无视轮数强制压一次，估算失败回 false 走原轮数门禁
            boolean forceByTokens = isOverTokenThreshold(conversationId, conversation);
            // 首道门禁：user 消息数未达触发阈值直接返回（token 超限时跳过该门禁）
            long userMessageCount = countUserMessages(conversationId, null);
            if (userMessageCount < config.getTriggerTurns() && !forceByTokens) {
                return;
            }
            // 间隔门禁：首压直接过，否则新增 user 消息数不足 intervalTurns*2 则返回（强制压缩直接过）
            if (!isDueForCompress(conversation, config, conversationId, forceByTokens)) {
                return;
            }
            ReentrantLock lock = lockMap.computeIfAbsent(LOCK_KEY_PREFIX + conversationId,
                    k -> new ReentrantLock());
            lock.lock();
            try {
                // 双重检查：锁内重读水位，防止并发重复压缩
                ChatConversation fresh = chatConversationMapper.selectById(conversationId);
                if (fresh == null || !isDueForCompress(fresh, config, conversationId, forceByTokens)) {
                    return;
                }
                doCompress(conversationId, fresh, config);
            } finally {
                lock.unlock();
            }
        } catch (Exception e) {
            // 故障隔离：摘要失败仅日志，不影响主对话链路
            log.warn("会话摘要压缩失败，回退纯滑窗: conversationId={}, error={}", conversationId, e.getMessage());
        }
    }

    /**
     * 异步触发会话摘要压缩（供 T4 在 assistant 消息落库后调用一次）。
     *
     * @param conversationId 会话ID
     * @param userId         用户ID（归属校验）
     */
    public void compressIfNeededAsync(Long conversationId, Long userId) {
        try {
            memorySummaryExecutor.execute(() -> compressIfNeeded(conversationId, userId));
        } catch (Exception e) {
            log.warn("提交摘要压缩任务失败: conversationId={}, error={}", conversationId, e.getMessage());
        }
    }

    /**
     * 读取会话最新摘要原文（空则返回 null）。
     *
     * @param conversationId 会话ID
     * @return 摘要正文，无摘要返回 null
     */
    public String loadLatestSummary(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        try {
            ChatConversation conversation = chatConversationMapper.selectById(conversationId);
            if (conversation == null || conversation.getSummaryText() == null
                    || conversation.getSummaryText().isBlank()) {
                return null;
            }
            return conversation.getSummaryText();
        } catch (Exception e) {
            log.warn("读取会话摘要失败: conversationId={}, error={}", conversationId, e.getMessage());
            return null;
        }
    }

    /**
     * 为摘要包一层背景提示前缀（空摘要返回 null）。
     *
     * @param summary 摘要原文
     * @return 带前缀的摘要，空返回 null
     */
    public String decorateIfNeeded(String summary) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        return SUMMARY_PREFIX + summary;
    }

    /**
     * 判断是否到达压缩时机：无水位首压；有水位则水位后新增 user 消息数达到 intervalTurns*2。
     *
     * @param conversation   会话（含水位）
     * @param config         摘要配置
     * @param conversationId 会话ID
     * @return 是否需要压缩
     */
    private boolean isDueForCompress(ChatConversation conversation, AiConfig.MemorySummary config,
                                      Long conversationId) {
        return isDueForCompress(conversation, config, conversationId, false);
    }

    /**
     * 判断是否到达压缩时机（token 安全阀重载）：强制标记为 true 时直接返回 true，无视轮数门禁。
     *
     * @param conversation   会话（含水位）
     * @param config         摘要配置
     * @param conversationId 会话ID
     * @param force          token 占比超限强制标记
     * @return 是否需要压缩
     * @author H
     */
    private boolean isDueForCompress(ChatConversation conversation, AiConfig.MemorySummary config,
                                      Long conversationId, boolean force) {
        if (force) {
            return true;
        }
        Long waterline = conversation.getSummaryUpToMessageId();
        if (waterline == null) {
            return true;
        }
        long newUserMessages = countUserMessages(conversationId, waterline);
        return newUserMessages >= (long) config.getIntervalTurns() * 2;
    }

    /**
     * 判断上下文 token 占比是否超限：摘要 + 全量消息 content 经估算后除以上下文窗口。
     * 复用 doCompress 的全量查询写法；估算失败回 false 走原轮数门禁，不阻塞主链路。
     *
     * @param conversationId 会话ID
     * @param conversation   会话（含旧摘要）
     * @return 占比达到阈值返回 true，否则 false，异常返回 false
     * @author H
     */
    private boolean isOverTokenThreshold(Long conversationId, ChatConversation conversation) {
        try {
            LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ChatMessage::getConversationId, conversationId);
            wrapper.orderByAsc(ChatMessage::getId);
            List<ChatMessage> allMessages = chatMessageMapper.selectList(wrapper);
            List<String> parts = new ArrayList<>();
            if (conversation.getSummaryText() != null && !conversation.getSummaryText().isBlank()) {
                parts.add(conversation.getSummaryText());
            }
            if (allMessages != null) {
                for (ChatMessage message : allMessages) {
                    if (message.getContent() != null && !message.getContent().isEmpty()) {
                        parts.add(message.getContent());
                    }
                }
            }
            int estimated = ContextTokenEstimator.estimateMessages(parts);
            Integer window = aiConfig.getChat() != null ? aiConfig.getChat().getContextWindow() : null;
            int contextWindow = (window == null || window <= 0) ? 128000 : window;
            double percent = estimated * 100.0 / contextWindow;
            if (percent >= FORCE_COMPRESS_PERCENT) {
                log.info("token 占比超限，强制压缩: conversationId={}, percent={}", conversationId, percent);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("估算 token 占比失败，走原轮数门禁: conversationId={}, error={}", conversationId, e.getMessage());
            return false;
        }
    }

    /**
     * 执行一次压缩：取水位后 keepTurns 窗口外原文 transcript，旧摘要字段级 merge，覆盖写回。
     *
     * @param conversationId 会话ID
     * @param conversation   最新会话（含旧摘要与水位）
     * @param config         摘要配置
     */
    private void doCompress(Long conversationId, ChatConversation conversation, AiConfig.MemorySummary config) {
        // 读取全量消息（按 ID 升序，水位单调前移）
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getConversationId, conversationId);
        wrapper.orderByAsc(ChatMessage::getId);
        List<ChatMessage> allMessages = chatMessageMapper.selectList(wrapper);
        if (allMessages == null || allMessages.isEmpty()) {
            return;
        }
        // 最近 keepTurns 轮整轮保留：定位到倒数第 keepTurns 条 user 消息，窗口起点再按 overlap 回退
        int keepStartIndex = locateKeepStartIndex(allMessages, config.getKeepTurns(), config.getOverlapTurns());
        List<ChatMessage> toCompress = allMessages.subList(0, keepStartIndex);
        if (toCompress.isEmpty()) {
            return;
        }
        // 水位 = 被压缩区最大消息ID（幂等键，保证只前移不回退）
        Long waterline = toCompress.get(toCompress.size() - 1).getId();
        Long oldWaterline = conversation.getSummaryUpToMessageId();
        if (oldWaterline != null && waterline <= oldWaterline) {
            return;
        }
        String transcript = buildTranscript(toCompress, config.getMaxCharsSingle(), config.getMaxCharsTotal());
        if (transcript.isBlank()) {
            return;
        }
        String summaryPrompt = buildSummaryPrompt(conversation.getSummaryText(), transcript,
                config.getTargetTokens());
        // 摘要生成走主备 API 模型降级链，不碰 Ollama；temperature 0.3 保证归并稳定
        OpenAiChatOptions summaryOptions = OpenAiChatOptions.builder().temperature(0.3).build();
        Prompt prompt = new Prompt(List.of(new SystemMessage(SUMMARY_SYSTEM_PROMPT),
                new UserMessage(summaryPrompt)), summaryOptions);
        var response = modelRouter.callWithFallback(prompt);
        String newSummary = response.getResult() != null && response.getResult().getOutput() != null
                ? response.getResult().getOutput().getText()
                : null;
        if (newSummary == null || newSummary.isBlank()) {
            log.warn("摘要模型返回为空，保留旧摘要: conversationId={}", conversationId);
            return;
        }
        // 覆盖写：幂等键 (conversation_id + summary_up_to_message_id)
        ChatConversation update = new ChatConversation();
        update.setId(conversationId);
        update.setSummaryText(newSummary.trim());
        update.setSummaryUpToMessageId(waterline);
        chatConversationMapper.updateById(update);
        log.info("会话摘要已压缩: conversationId={}, waterline={}, summaryChars={}",
                conversationId, waterline, newSummary.length());
    }

    /**
     * 统计会话 user 消息数（afterMessageId 为空统计全部，否则统计水位后新增）。
     *
     * @param conversationId 会话ID
     * @param afterMessageId 水位消息ID（可空）
     * @return user 消息数
     */
    private long countUserMessages(Long conversationId, Long afterMessageId) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getConversationId, conversationId);
        wrapper.eq(ChatMessage::getRole, MessageRoleEnum.USER.getCode());
        if (afterMessageId != null) {
            wrapper.gt(ChatMessage::getId, afterMessageId);
        }
        return chatMessageMapper.selectCount(wrapper);
    }

    /**
     * 定位保留窗口起点下标：倒数第 keepTurns 条 user 消息所在位置，再按 overlapTurns 轮前移。
     * 整轮保留（含 tool-call 相邻消息不被拦腰截断，由调用方按轮拼装时保证原子）。
     *
     * @param allMessages  全量升序消息
     * @param keepTurns    保留轮数
     * @param overlapTurns 重叠轮数
     * @return 保留窗口起点下标
     */
    private int locateKeepStartIndex(List<ChatMessage> allMessages, int keepTurns, int overlapTurns) {
        List<Integer> userIndexes = new ArrayList<>();
        for (int i = 0; i < allMessages.size(); i++) {
            if (MessageRoleEnum.USER.getCode().equals(allMessages.get(i).getRole())) {
                userIndexes.add(i);
            }
        }
        if (userIndexes.isEmpty()) {
            return allMessages.size();
        }
        // 保留最近 keepTurns 轮：起点为倒数第 keepTurns 条 user 消息
        int anchor = userIndexes.get(Math.max(0, userIndexes.size() - keepTurns));
        // 重叠回退：按轮折算消息数近似前移，避免边界信息丢失
        int overlapMessages = Math.max(0, overlapTurns) * 2;
        return Math.max(0, anchor - overlapMessages);
    }

    /**
     * 构建原文 transcript：单条按 maxCharsSingle 截断，总量按 maxCharsTotal 截断（整条保留）。
     *
     * @param messages      待压缩消息（升序）
     * @param maxCharsSingle 单条最大字符数
     * @param maxCharsTotal  总量最大字符数
     * @return transcript 文本
     */
    private String buildTranscript(List<ChatMessage> messages, int maxCharsSingle, int maxCharsTotal) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : messages) {
            String roleLabel = resolveRoleLabel(message.getRole());
            String content = message.getContent() == null ? "" : message.getContent();
            if (content.length() > maxCharsSingle) {
                content = content.substring(0, maxCharsSingle) + "…（截断）";
            }
            String line = "[" + roleLabel + "] " + content + "\n";
            if (sb.length() + line.length() > maxCharsTotal) {
                break;
            }
            sb.append(line);
        }
        return sb.toString().trim();
    }

    /**
     * 构建摘要输入：旧摘要仅作字段级 merge 参考，不当事实重喂；防几何退化以原文 transcript 为主。
     *
     * @param oldSummary  旧摘要（可空）
     * @param transcript  原文 transcript
     * @param targetTokens 摘要目标 token 数
     * @return 摘要提示词正文
     */
    private String buildSummaryPrompt(String oldSummary, String transcript, int targetTokens) {
        StringBuilder sb = new StringBuilder();
        sb.append("请将以下历史对话压缩为约 ").append(targetTokens).append(" tokens 的结构化摘要。\n");
        if (oldSummary != null && !oldSummary.isBlank()) {
            sb.append("【旧摘要（仅供字段级 merge 去重参考：已收录事实不要重复展开，冲突处以新对话为准）】\n");
            sb.append(oldSummary.trim()).append("\n");
        }
        sb.append("【新增历史对话原文（以此为准归纳）】\n");
        sb.append(transcript);
        return sb.toString();
    }

    /**
     * 解析消息角色标签。
     *
     * @param role 角色码
     * @return 角色标签
     */
    private String resolveRoleLabel(Integer role) {
        MessageRoleEnum roleEnum = MessageRoleEnum.fromCode(role);
        return roleEnum == null ? "unknown" : roleEnum.getDesc();
    }

    /**
     * 关闭异步线程池。
     */
    @PreDestroy
    public void shutdown() {
        try {
            memorySummaryExecutor.shutdown();
        } catch (Exception e) {
            log.warn("关闭摘要线程池失败: {}", e.getMessage());
        }
    }
}
