package com.hagent.ai.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.FluxSink;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 思考阶段支持组件
 * 集中管理 8 阶段思考标题常量、阶段推断、thinking/stage 事件包装与流式发射，
 * 供 ChatServiceImpl 与 AgentToolLoopService 共用
 *
 * @author H
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThinkingStageSupport {

    /** JSON序列化工具，用于 SSE 事件结构化编码 */
    private final ObjectMapper objectMapper;

    // ===== 思考阶段标题（8 阶段动态标题，前端据此切换折叠块标题，已去除省略号保持和谐） =====
    /** 初始思考 */
    public static final String THINK_STAGE_INITIAL = "思考中";
    /** 正在理解问题 */
    public static final String THINK_STAGE_UNDERSTAND = "正在理解问题";
    /** 正在分析问题 */
    public static final String THINK_STAGE_ANALYZE = "正在分析问题";
    /** 制定解题策略 */
    public static final String THINK_STAGE_STRATEGY = "制定解题策略";
    /** 检索知识库 */
    public static final String THINK_STAGE_RETRIEVE = "检索知识库";
    /** 生成查询 SQL */
    public static final String THINK_STAGE_SQL = "生成查询 SQL";
    /** 构建输出结构 */
    public static final String THINK_STAGE_STRUCTURE = "构建输出结构";
    /** 最终润色 */
    public static final String THINK_STAGE_POLISH = "最终润色";
    /** 思考结束（流式完成后的最终标题） */
    public static final String THINK_STAGE_DONE = "思考结束";
    /** 8 阶段标题有序列表（供前端阶段推演） */
    public static final List<String> THINK_STAGE_ORDERED = List.of(
            THINK_STAGE_INITIAL, THINK_STAGE_UNDERSTAND, THINK_STAGE_ANALYZE,
            THINK_STAGE_STRATEGY, THINK_STAGE_RETRIEVE, THINK_STAGE_SQL,
            THINK_STAGE_STRUCTURE, THINK_STAGE_POLISH);

    /** 思考内容分片大小（用于非流式场景的模拟流式分片） */
    public static final int THINKING_CHUNK_SIZE = 30;

    /** 思考分片间的发射间隔，确保前端收到事件在时间上分散而非一次性爆发 */
    public static final java.time.Duration THINKING_DELAY = java.time.Duration.ofMillis(30);

    /** RAG检索工具名称（与 KnowledgeTool.searchKnowledge 对应） */
    public static final String RAG_TOOL_NAME = "searchKnowledge";
    /** Text-to-SQL 执行器工具名称 */
    public static final String SQL_TOOL_NAME = "queryBusinessData";
    /** 图片识别工具名称 */
    public static final String IMAGE_TOOL_NAME = "analyze_image";

    /** 追问提示词过滤阈值：长度超过该值且包含追问助手标识的 thinking 直接过滤不推送 */
    private static final int FOLLOWUP_PROMPT_LENGTH_THRESHOLD = 500;
    /** 追问助手标识 */
    private static final String FOLLOWUP_MARKER = "你是HAgent追问助手";
    /** 同一 thinking 文本去重窗口（毫秒）：5s 内重复不再推送 */
    private static final long THINKING_DEDUP_WINDOW_MS = 5000L;
    /** 去重缓存：key -> 首次推送时间戳 */
    private static final ConcurrentHashMap<String, Long> THINKING_DEDUP_CACHE = new ConcurrentHashMap<>();
    /** 去重缓存最大条目数，超出时清理过期键 */
    private static final int DEDUP_CACHE_MAX_SIZE = 1000;

    /**
     * 根据思考文本推断阶段标题（关键词映射，前端可据 stage 字段切换标题）
     * 若无明显关键词则返回 null，由调用方决定是否附带 stage
     */
    @Nullable
    public String inferThinkingStage(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return null;
        }
        String lower = chunk.toLowerCase();
        // 优先级从具体到宽泛，避免误判
        if (lower.contains("sql") || chunk.contains("查询") || chunk.contains("SELECT") || chunk.contains("select")) {
            return THINK_STAGE_SQL;
        }
        if (chunk.contains("检索") || chunk.contains("知识库") || lower.contains("rag") || lower.contains("searchknowledge")) {
            return THINK_STAGE_RETRIEVE;
        }
        if (chunk.contains("策略") || chunk.contains("方案") || chunk.contains("计划") || lower.contains("strategy")) {
            return THINK_STAGE_STRATEGY;
        }
        if (chunk.contains("结构") || chunk.contains("组织") || chunk.contains("构建输出") || lower.contains("structure")) {
            return THINK_STAGE_STRUCTURE;
        }
        if (chunk.contains("润色") || chunk.contains("优化") || chunk.contains("完善") || lower.contains("polish")) {
            return THINK_STAGE_POLISH;
        }
        if (chunk.contains("分析")) {
            return THINK_STAGE_ANALYZE;
        }
        if (chunk.contains("理解")) {
            return THINK_STAGE_UNDERSTAND;
        }
        return null;
    }

    /**
     * 将阶段进度包装为结构化JSON字符串
     * 前端据 type=stage 字段展示阶段进度（如"正在理解问题"）
     *
     * @param content 阶段文案
     * @return 结构化JSON字符串，格式：{"type":"stage","content":"<阶段文案>"}
     */
    public String wrapStage(String content) {
        try {
            return "{\"type\":\"stage\",\"content\":" + objectMapper.writeValueAsString(content) + "}";
        } catch (JacksonException e) {
            log.warn("JSON编码stage失败，降级为空内容", e);
            return "{\"type\":\"stage\",\"content\":\"\"}";
        }
    }

    /**
     * 根据工具名推送对应阶段标题（前端据此切换折叠块标题）
     * searchKnowledge → 检索知识库，queryBusinessData → 生成查询 SQL，analyze_image → 构建输出结构
     */
    public void emitStageForTool(FluxSink<String> sink, String toolName) {
        if (sink.isCancelled()) {
            return;
        }
        try {
            String stage = null;
            if (RAG_TOOL_NAME.equals(toolName)) {
                stage = THINK_STAGE_RETRIEVE;
            } else if (SQL_TOOL_NAME.equals(toolName)) {
                stage = THINK_STAGE_SQL;
            } else if (IMAGE_TOOL_NAME.equals(toolName)) {
                stage = THINK_STAGE_STRUCTURE;
            }
            if (stage != null) {
                sink.next(wrapStage(stage));
            }
        } catch (Exception e) {
            log.warn("推送工具对应阶段失败: {}", toolName, e);
        }
    }

    /**
     * 将思考/推理增量包装为结构化JSON字符串（兼容旧调用：推断阶段后带 stage 一并下发）
     * 增加追问提示词泄漏防护：长度 >500 且含追问标识的 chunk 直接返回空 thinking 事件由调用方丢弃
     *
     * @param thinkingChunk 思考内容增量
     * @return 结构化JSON字符串，格式：{"type":"thinking","content":"<思考增量>"}，泄漏时返回空 content
     * @author H
     */
    public String wrapThinking(String thinkingChunk) {
        // 追问提示词泄漏过滤（直接返回空，避免前端展示 4 次回环）
        if (isFollowupPromptLeak(thinkingChunk)) {
            log.warn("wrapThinking 过滤追问提示词泄漏: length={}", thinkingChunk.length());
            return "{\"type\":\"thinking\",\"content\":\"\"}";
        }
        String stage = inferThinkingStage(thinkingChunk);
        if (stage != null) {
            return wrapThinking(thinkingChunk, stage);
        }
        try {
            return "{\"type\":\"thinking\",\"content\":" + objectMapper.writeValueAsString(thinkingChunk) + "}";
        } catch (JacksonException e) {
            log.warn("JSON编码thinking失败，降级为空内容", e);
            return "{\"type\":\"thinking\",\"content\":\"\"}";
        }
    }

    /**
     * 将思考增量与阶段提示一并包装（供前端动态标题切换）
     *
     * @param thinkingChunk 思考内容增量
     * @param stage 阶段标题（8 阶段之一）
     * @return 结构化JSON：{"type":"thinking","content":"...","stage":"..."}，泄漏时返回空 content
     * @author H
     */
    public String wrapThinking(String thinkingChunk, String stage) {
        if (isFollowupPromptLeak(thinkingChunk)) {
            log.warn("wrapThinking(stage) 过滤追问提示词泄漏: length={}", thinkingChunk.length());
            return "{\"type\":\"thinking\",\"content\":\"\"}";
        }
        try {
            String contentJson = objectMapper.writeValueAsString(thinkingChunk);
            String stageJson = objectMapper.writeValueAsString(stage);
            return "{\"type\":\"thinking\",\"content\":" + contentJson + ",\"stage\":" + stageJson + "}";
        } catch (JacksonException e) {
            log.warn("JSON编码thinking+stage失败，降级为仅content", e);
            try {
                return "{\"type\":\"thinking\",\"content\":" + objectMapper.writeValueAsString(thinkingChunk) + "}";
            } catch (JacksonException ex) {
                return "{\"type\":\"thinking\",\"content\":\"\"}";
            }
        }
    }

    /**
     * 将思考内容按固定大小切分为多段
     *
     * @param thinking  完整思考内容
     * @param chunkSize 切分大小
     * @return 切分后的片段列表
     */
    public List<String> splitThinking(String thinking, int chunkSize) {
        List<String> pieces = new ArrayList<>();
        for (int start = 0; start < thinking.length(); start += chunkSize) {
            int end = Math.min(start + chunkSize, thinking.length());
            pieces.add(thinking.substring(start, end));
        }
        return pieces;
    }

    /**
     * 判断 thinking 是否应被过滤（追问提示词回环防护）
     * 当 thinking 长度超过阈值且包含“你是HAgent追问助手”标识时，视为追问提示词误入 thinking 通道，直接过滤
     *
     * @param thinkingChunk 思考增量
     * @return 是否应过滤
     * @author H
     */
    public static boolean isFollowupPromptLeak(String thinkingChunk) {
        if (thinkingChunk == null) {
            return false;
        }
        return thinkingChunk.length() > FOLLOWUP_PROMPT_LENGTH_THRESHOLD && thinkingChunk.contains(FOLLOWUP_MARKER);
    }

    /**
     * 同一 conversation 下同一 thinking 文本 5s 内去重
     * 使用 conversationId + thinking 内容哈希作为 key，5s 内重复推送直接丢弃
     *
     * @param conversationId 会话ID（可空，空时按全局去重）
     * @param thinkingChunk 思考增量
     * @return 是否允许推送（true 允许，false 去重丢弃）
     * @author H
     */
    public static boolean shouldEmitThinking(Long conversationId, String thinkingChunk) {
        if (thinkingChunk == null || thinkingChunk.isEmpty()) {
            return false;
        }
        // 追问提示词泄漏直接过滤
        if (isFollowupPromptLeak(thinkingChunk)) {
            log.warn("thinking 追问提示词泄漏已过滤: length={} conversationId={}", thinkingChunk.length(), conversationId);
            return false;
        }
        // 生成去重 key：conversationId + 内容哈希/前 200 字符，避免超长 key
        String contentKey = thinkingChunk.length() <= 200 ? thinkingChunk : thinkingChunk.substring(0, 200) + "#" + thinkingChunk.length();
        String key = (conversationId != null ? conversationId : "global") + ":" + contentKey.hashCode();
        long now = System.currentTimeMillis();
        Long last = THINKING_DEDUP_CACHE.get(key);
        if (last != null && (now - last) < THINKING_DEDUP_WINDOW_MS) {
            log.debug("thinking 去重丢弃: conversationId={} keyHash={}", conversationId, contentKey.hashCode());
            return false;
        }
        // 清理过期：超量时移除过期条目
        if (THINKING_DEDUP_CACHE.size() > DEDUP_CACHE_MAX_SIZE) {
            THINKING_DEDUP_CACHE.entrySet().removeIf(e -> (now - e.getValue()) > THINKING_DEDUP_WINDOW_MS);
        }
        THINKING_DEDUP_CACHE.put(key, now);
        return true;
    }

    /**
     * 将思考增量实时写入 SSE sink
     * 大块增量按 THINKING_CHUNK_SIZE 切分、分片间休眠 THINKING_DELAY，模拟逐字输出且保持与后续事件的先后顺序
     * 增加过滤与去重：追问长提示词直接过滤，同一 conversation 同一 thinking 5s 内不再重复推送
     *
     * @param sink          SSE 事件sink
     * @param thinkingChunk 本次思考增量
     * @return 实际写入的增量片段（用于调用方去重累积，已切分时为拼接后的 chunk），被过滤/去重时返回 null
     * @author H
     */
    public String emitThinkingToSink(FluxSink<String> sink, String thinkingChunk) {
        if (thinkingChunk == null || thinkingChunk.isEmpty()) {
            return null;
        }
        // 追问提示词泄漏过滤
        if (isFollowupPromptLeak(thinkingChunk)) {
            log.warn("thinking 追问提示词泄漏已过滤不推送: length={}", thinkingChunk.length());
            return null;
        }
        // 从 UserContextHolder 尝试获取 conversationId 用于去重
        Long cid = null;
        try {
            cid = com.hagent.ai.core.UserContextHolder.getConversationId();
        } catch (Exception ignored) {
        }
        if (!shouldEmitThinking(cid, thinkingChunk)) {
            return null;
        }
        if (thinkingChunk.length() <= 50) {
            // 小增量直接发射，体现流式 delta 透传
            sink.next(wrapThinking(thinkingChunk));
            return thinkingChunk;
        }
        for (String piece : splitThinking(thinkingChunk, THINKING_CHUNK_SIZE)) {
            // 客户端断连后停止发射
            if (sink.isCancelled()) {
                return null;
            }
            sink.next(wrapThinking(piece));
            sleepQuietly(THINKING_DELAY.toMillis());
        }
        return thinkingChunk;
    }

    /**
     * 计算思考增量的去重后新增部分
     * 兼容两种网关行为：delta（仅新增）与全量重放（每次返回已见的完整 thinking）。
     * 若新帧以旧累积为前缀，则仅返回新增后缀；否则视为新 delta 原样返回
     *
     * @param prevAccum 已累积的 thinking
     * @param newChunk  本次提取的 thinking 帧
     * @return 去重后的新增部分，重复/空时返回 null
     * @author H
     */
    public static String dedupThinkingDelta(String prevAccum, String newChunk) {
        if (newChunk == null || newChunk.isEmpty()) {
            return null;
        }
        if (prevAccum == null || prevAccum.isEmpty()) {
            return newChunk;
        }
        if (newChunk.equals(prevAccum)) {
            return null;
        }
        if (newChunk.startsWith(prevAccum)) {
            String delta = newChunk.substring(prevAccum.length());
            return delta.isEmpty() ? null : delta;
        }
        // 非前缀的新内容：可能是不同阶段的独立 thinking，视为新 delta
        return newChunk;
    }

    /**
     * 从 ChatResponse 中提取思考/推理内容
     * 兼容多种来源与键名：AssistantMessage/Generation/ChatResponse metadata 中的
     * reasoning_content / reasoningContent / reasoning / thinking
     *
     * @param response ChatModel 返回的响应对象
     * @return 思考内容字符串，不存在时返回 null
     */
    @Nullable
    public String extractThinking(ChatResponse response) {
        List<String> keys = List.of("reasoning_content", "reasoningContent", "reasoning", "thinking");
        if (response == null) {
            return null;
        }
        if (response.getResult() != null && response.getResult().getOutput() != null) {
            Map<String, Object> meta = response.getResult().getOutput().getMetadata();
            if (meta != null && !meta.isEmpty()) {
                String v = getThinkingFromMap(meta);
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
            // Generation metadata（部分版本/网关将 reasoning 写入此处，非 Map 类型，用其自身 containsKey/get）
            var genMeta = response.getResult().getMetadata();
            if (genMeta != null) {
                for (String key : keys) {
                    if (genMeta.containsKey(key)) {
                        Object obj = genMeta.get(key);
                        if (obj instanceof String s && !s.isBlank()) {
                            return s;
                        }
                    }
                }
            }
        }
        // ChatResponse 级别 metadata 兜底（兼容不同网关/代理实现）
        var respMeta = response.getMetadata();
        if (respMeta != null) {
            for (String key : keys) {
                if (respMeta.containsKey(key)) {
                    Object obj = respMeta.get(key);
                    if (obj instanceof String s && !s.isBlank()) {
                        return s;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 从 Map 中按优先级提取思考内容
     *
     * @param meta 元数据 Map
     * @return 思考内容字符串，不存在时返回 null
     */
    @Nullable
    public String getThinkingFromMap(Map<String, Object> meta) {
        // 优先级：reasoning_content > reasoningContent > reasoning > thinking
        for (String key : List.of("reasoning_content", "reasoningContent", "reasoning", "thinking")) {
            Object v = meta.get(key);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    /**
     * 休眠指定毫秒（思考分片发射间隔），被中断时恢复中断标记并立即返回
     *
     * @param millis 休眠毫秒数
     */
    public void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
