package com.hagent.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hagent.ai.core.AiModelProvider;
import com.hagent.ai.core.UserContextHolder;
import com.hagent.ai.mapper.ChatMessageMapper;
import com.hagent.ai.model.entity.ChatMessage;
import com.hagent.ai.service.QueryRewriteService;
import com.hagent.common.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 查询补全改写服务实现（P0-4 轻量版）
 * 两段式：同义词映射（system_config 配置，零成本）→ LLM 指代补全（最近对话拼接，一轮调用）
 * 设计约束：任一环节失败静默回退原查询；结果按会话+原查询缓存，避免同一回答内重复改写开销；
 * LLM 连续超时自动熔断（跨请求进程级），冷却期后探测恢复，防止改写模型不可用时拖垮每次检索
 * 对应配置键：ai.query-rewrite.enabled（默认 true）、ai.query-rewrite.synonyms（默认空，格式 口语词=文档词，分号或换行分隔）
 *
 * @author H
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriteServiceImpl implements QueryRewriteService {

    /** 改写开关配置键 */
    private static final String CONFIG_ENABLED = "ai.query-rewrite.enabled";

    /** 同义词映射配置键 */
    private static final String CONFIG_SYNONYMS = "ai.query-rewrite.synonyms";

    /** 参与改写的最近消息条数（2 轮对话） */
    private static final int HISTORY_LIMIT = 4;

    /** 单条历史消息截断长度，控制改写提示词成本 */
    private static final int HISTORY_CLIP_LENGTH = 200;

    /** 触发 LLM 改写的查询长度上限：过长查询通常已完整，直接跳过 */
    private static final int REWRITE_QUERY_MAX_LENGTH = 50;

    /** LLM 改写硬超时（毫秒）：改写是轻量任务，3s 未返回即回退原查询（推理模型触发完整思考时远超此值，交由熔断兜底） */
    private static final long REWRITE_TIMEOUT_MS = 3_000L;

    /** 改写熔断阈值：LLM 连续超时达到该次数后开启熔断，冷却期内跳过 LLM 改写（同义映射不受影响） */
    private static final int CIRCUIT_OPEN_THRESHOLD = 2;

    /** 改写熔断冷却时长（毫秒）：冷却结束后放行一次探测调用，成功则复位，再超时则刷新熔断窗口 */
    private static final long CIRCUIT_COOLDOWN_MS = 60_000L;

    /** LLM 改写连续超时计数（进程级，跨请求共享：模型健康度是全局属性） */
    private final AtomicInteger llmTimeoutStreak = new AtomicInteger();

    /** 熔断开启时刻（毫秒时间戳），0 表示从未熔断 */
    private volatile long circuitOpenedAtMs = 0L;

    /** 改写系统提示词：只输出查询本身，无法改进时原样输出 */
    private static final String REWRITE_SYSTEM_PROMPT =
            "你是检索查询改写器。结合最近对话，把最后一问改写成不依赖上下文、可直接用于知识库检索的完整查询。"
                    + "补全指代与省略的主语，保留原意与关键词，不回答、不解释，只输出改写后的查询本身；"
                    + "已完整时原样输出，不要加引号或任何前缀。";

    /** 改写结果缓存：key = conversationId|查询，按访问序淘汰，容量 256，跨请求复用避免同一回答内重复 LLM 调用 */
    private final Map<String, String> rewriteCache =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 256;
                }
            };

    private final SystemConfigService systemConfigService;
    private final AiModelProvider aiModelProvider;
    private final ChatMessageMapper chatMessageMapper;

    @Override
    public String rewrite(String query) {
        String original = query == null ? "" : query.trim();
        if (original.isEmpty()) {
            return original;
        }
        // 第一段：同义词映射，零成本且永远生效
        String rewritten = applySynonyms(original);
        // 第二段：LLM 指代补全，开关关闭或无会话上下文时跳过
        if (!isLlmRewriteEnabled()) {
            return rewritten;
        }
        Long conversationId = UserContextHolder.getConversationId();
        if (conversationId == null) {
            return rewritten;
        }
        List<String> historyLines = loadRecentHistory(conversationId);
        if (historyLines.isEmpty()) {
            // 首条消息无上下文可补全，跳过
            return rewritten;
        }
        if (rewritten.length() > REWRITE_QUERY_MAX_LENGTH) {
            // 过长查询通常已完整，跳过 LLM 环节
            return rewritten;
        }
        String cacheKey = conversationId + "|" + rewritten;
        synchronized (rewriteCache) {
            String cached = rewriteCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        if (isCircuitOpen()) {
            // 连续超时熔断：冷却期内跳过 LLM 改写，同义映射结果直接生效
            return rewritten;
        }
        try {
            String llmResult = llmRewrite(historyLines, rewritten);
            String finalQuery = StringUtils.hasText(llmResult) ? llmResult.trim() : rewritten;
            synchronized (rewriteCache) {
                rewriteCache.put(cacheKey, finalQuery);
            }
            if (!finalQuery.equals(rewritten)) {
                log.info("检索查询已改写: conversationId={}, 原查询={}, 改写后={}", conversationId, rewritten, finalQuery);
            }
            return finalQuery;
        } catch (Exception e) {
            // 静默回退：改写失败不影响检索主链路
            log.warn("检索查询改写失败，回退原查询: query={}, error={}", rewritten, e.getMessage());
            return rewritten;
        }
    }

    /**
     * 应用同义词映射：读 ai.query-rewrite.synonyms，按 键=值 解析（分号或换行分隔），键按长度降序替换防止前缀遮蔽
     *
     * @param query 原始查询
     * @return 映射后的查询
     * @author H
     */
    private String applySynonyms(String query) {
        String raw = systemConfigService.getValueByKey(CONFIG_SYNONYMS, "");
        if (!StringUtils.hasText(raw)) {
            return query;
        }
        List<Map.Entry<String, String>> pairs = new ArrayList<>();
        for (String entry : raw.split("[;\\r\\n]+")) {
            int idx = entry.indexOf('=');
            if (idx > 0 && idx < entry.length() - 1) {
                pairs.add(new AbstractMap.SimpleEntry<>(entry.substring(0, idx).trim(), entry.substring(idx + 1).trim()));
            }
        }
        if (pairs.isEmpty()) {
            return query;
        }
        // 键长的先替换，避免短键先替换破坏长键匹配
        pairs.sort(Comparator.comparingInt((Map.Entry<String, String> p) -> p.getKey().length()).reversed());
        String result = query;
        for (Map.Entry<String, String> pair : pairs) {
            result = result.replace(pair.getKey(), pair.getValue());
        }
        return result;
    }

    /**
     * LLM 一轮改写：最近对话 + 当前查询拼接，输出独立完整查询
     *
     * @param historyLines 最近对话行（user:/assistant: 前缀）
     * @param query        当前查询
     * @return 改写后的查询；模型输出为空时返回 null（调用方回退）
     * @author H
     */
    private String llmRewrite(List<String> historyLines, String query) {
        ChatModel chatModel = aiModelProvider.getChatModel();
        if (chatModel == null) {
            return null;
        }
        StringBuilder context = new StringBuilder();
        for (String line : historyLines) {
            context.append(line).append('\n');
        }
        context.append("最后一问：").append(query);
        List<Message> messages = List.of(
                new SystemMessage(REWRITE_SYSTEM_PROMPT),
                new UserMessage(context.toString()));
        // 硬超时包装：改写是轻量任务，超时即放弃回退原查询（超时后底层 HTTP 连接由 supplyAsync 线程自然结束或由 cancel 中断）
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> invokeRewrite(chatModel, messages));
        try {
            String result = future.get(REWRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            // 调用按时返回（无论输出是否有效）：模型未挂起，复位熔断计数
            llmTimeoutStreak.set(0);
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("查询改写超时（{}ms），回退原查询: query={}", REWRITE_TIMEOUT_MS, query);
            recordLlmTimeout(query);
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof InterruptedException) {
                // SSE 取消等场景传播的中断：恢复标志位后回退
                Thread.currentThread().interrupt();
                return null;
            }
            log.warn("查询改写调用失败: query={}, error={}", query, cause.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 同步执行 LLM 改写调用与输出清洗（在独立线程中运行，供超时包装）
     *
     * @param chatModel 对话模型
     * @param messages  改写消息列表
     * @return 改写后的查询；模型输出为空时返回 null（调用方回退）
     * @author H
     */
    private String invokeRewrite(ChatModel chatModel, List<Message> messages) {
        var response = chatModel.call(new Prompt(messages));
        String text = response.getResult() != null && response.getResult().getOutput() != null
                ? response.getResult().getOutput().getText()
                : null;
        if (!StringUtils.hasText(text)) {
            return null;
        }
        // 清洗输出：去引号/换行/markdown 标记，只保留首行
        return text.trim()
                .replaceAll("^[#*\\s`\"'“”‘’]+", "")
                .replaceAll("[#*`\"'“”‘’\\s]+$", "")
                .replaceAll("[\\r\\n].*$", "")
                .trim();
    }

    /**
     * 加载最近对话（USER/ASSISTANT 各取，最近 4 条，正序返回）
     *
     * @param conversationId 会话 ID
     * @return 对话行列表，格式 user: xxx / assistant: xxx；无历史返回空列表
     * @author H
     */
    private List<String> loadRecentHistory(Long conversationId) {
        try {
            LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ChatMessage::getConversationId, conversationId)
                    .in(ChatMessage::getRole, 1, 2)
                    .isNotNull(ChatMessage::getContent)
                    .orderByDesc(ChatMessage::getId)
                    .last("LIMIT " + HISTORY_LIMIT);
            List<ChatMessage> latest = chatMessageMapper.selectList(wrapper);
            List<String> lines = new ArrayList<>();
            for (int i = latest.size() - 1; i >= 0; i--) {
                ChatMessage message = latest.get(i);
                String content = message.getContent() == null ? "" : message.getContent().trim();
                if (content.isEmpty()) {
                    continue;
                }
                if (content.length() > HISTORY_CLIP_LENGTH) {
                    content = content.substring(0, HISTORY_CLIP_LENGTH);
                }
                lines.add(("user".equalsIgnoreCase(roleName(message.getRole())) ? "user: " : "assistant: ") + content);
            }
            return lines;
        } catch (Exception e) {
            // 历史加载失败不影响改写主链路
            log.warn("加载最近对话失败，跳过 LLM 改写: conversationId={}, error={}", conversationId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 角色码转文本（1=user，2=assistant，其余归 assistant）
     *
     * @param code 角色码
     * @return 角色文本
     * @author H
     */
    private String roleName(Integer code) {
        return code != null && code == 1 ? "user" : "assistant";
    }

    /**
     * LLM 改写开关是否开启（ai.query-rewrite.enabled，默认 true，非法值视为开启）
     *
     * @return 是否开启
     * @author H
     */
    private boolean isLlmRewriteEnabled() {
        String enabled = systemConfigService.getValueByKey(CONFIG_ENABLED, "true");
        return !"false".equalsIgnoreCase(enabled == null ? "true" : enabled.trim());
    }

    /**
     * 记录一次 LLM 改写超时：连续超时达到阈值时开启熔断（进程级，跨请求共享）
     *
     * @param query 当前查询（仅日志用）
     * @author H
     */
    private void recordLlmTimeout(String query) {
        int streak = llmTimeoutStreak.incrementAndGet();
        if (streak >= CIRCUIT_OPEN_THRESHOLD) {
            circuitOpenedAtMs = System.currentTimeMillis();
            log.warn("查询改写连续超时 {} 次，开启熔断 {}s：冷却期内跳过 LLM 改写（同义映射不受影响）: query={}",
                    streak, CIRCUIT_COOLDOWN_MS / 1000, query);
        }
    }

    /**
     * 改写熔断是否生效：连续超时达到阈值且处于冷却期内返回 true；
     * 冷却结束后放行一次探测调用，按时返回则复位，再超时则刷新熔断窗口
     *
     * @return 是否应跳过 LLM 改写
     * @author H
     */
    private boolean isCircuitOpen() {
        if (llmTimeoutStreak.get() < CIRCUIT_OPEN_THRESHOLD) {
            return false;
        }
        return System.currentTimeMillis() - circuitOpenedAtMs < CIRCUIT_COOLDOWN_MS;
    }
}
