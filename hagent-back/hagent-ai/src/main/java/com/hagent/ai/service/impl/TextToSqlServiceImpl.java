package com.hagent.ai.service.impl;

import com.hagent.ai.core.AiModelProvider;
import com.hagent.ai.core.DatabaseSchemaProvider;
import com.hagent.ai.core.SqlSecurityValidator;
import com.hagent.ai.core.UserContextHolder;
import com.hagent.ai.model.entity.ChatPendingAction;
import com.hagent.ai.service.PendingActionService;
import com.hagent.ai.service.TextToSqlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Text to SQL 服务实现
 *
 * @author H
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TextToSqlServiceImpl implements TextToSqlService {

    private final AiModelProvider aiModelProvider;
    private final DatabaseSchemaProvider schemaProvider;
    private final SqlSecurityValidator sqlValidator;
    private final JdbcTemplate jdbcTemplate;
    private final PendingActionService pendingActionService;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    /** 待确认操作过期时间（小时） */
    private static final int PENDING_EXPIRE_HOURS = 24;

    /** 最大返回行数 */
    private static final int MAX_RESULT_ROWS = 100;

    /** 操作类型：知识库/数据类操作 */
    private static final int ACTION_TYPE_KNOWLEDGE = 3;

    /** 思考内容分片大小（用于非流式场景的模拟流式分片，保持与 ChatServiceImpl 一致） */
    private static final int THINKING_CHUNK_SIZE = 30;

    /** 思考分片间的发射间隔，确保前端收到 thinking 事件在时间上分散而非一次性爆发 */
    private static final Duration THINKING_DELAY = Duration.ofMillis(30);

    /**
     * 系统提示词模板：允许 SELECT/INSERT/UPDATE/DELETE，受控生成单条语句（PostgreSQL 方言）
     * 提示词中的 LIMIT 100 对应 {@link #MAX_RESULT_ROWS}，与校验器 {@code SqlSecurityValidator#DEFAULT_MAX_ROWS} 保持一致
     */
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是一个 PostgreSQL 专家。根据用户的自然语言问题，生成对应的 PostgreSQL 语句.

            %s

            规则：
            1. 只允许生成单条 SELECT / INSERT / UPDATE / DELETE 语句，禁止生成 DDL/DCL（DROP/TRUNCATE/ALTER/CREATE等）、多语句或注释
            2. SELECT 查询必须添加 WHERE deleted = 0 条件，且必须包含 LIMIT 子句（最多100行）；若用户未指定数量则默认 LIMIT 100
            3. UPDATE / DELETE 必须包含 WHERE 条件，禁止全表操作
            4. 使用标准 PostgreSQL 语法，字段名使用下划线命名法（如 order_no、created_at）；日期时间使用 NOW()/CURRENT_DATE/DATE_TRUNC('day', col)/EXTRACT(YEAR FROM col)/EXTRACT(MONTH FROM col)，模糊匹配用 ILIKE，字符串拼接用 ||，空值处理用 COALESCE（禁用 MySQL 的 DATE()/YEAR()/MONTH()/DATE_FORMAT/IFNULL）
            5. 涉及金额的字段使用 DECIMAL 类型，保留2位小数；统计查询使用合适的聚合函数（SUM、COUNT、AVG、MAX、MIN），排序默认 DESC 降序
            6. 只返回 SQL 语句本身，不要包含任何解释、注释或 markdown 标记
            7. 如果问题无法用 SQL 回答，返回 "ERROR: 无法回答此问题"
            """;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> queryByNaturalLanguage(String question) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // 1. 调用 AI 生成 SQL（支持 SELECT/INSERT/UPDATE/DELETE 单条语句）
            String sql = generateSql(question);
            log.info("AI生成的SQL: {}", sql);

            // 检查是否是错误响应（AI 返回 ERROR: 前缀表示无法回答）
            if (sql.startsWith("ERROR:")) {
                result.put("success", false);
                result.put("message", sql.substring(6).trim());
                return result;
            }

            // 2. 安全校验（双通道路由：SELECT 与 WRITE）
            SqlSecurityValidator.ValidationResult validation = sqlValidator.validate(sql);
            if (!validation.isValid()) {
                result.put("success", false);
                result.put("message", "SQL安全校验失败: " + validation.getMessage());
                return result;
            }

            String sanitizedSql = validation.getSanitizedSql();
            String statementType = validation.getStatementType();
            log.info("校验后的SQL: {}, statementType={}", sanitizedSql, statementType);

            // 3. 按语句类型分支处理：SELECT 直查，WRITE 走待确认
            if ("SELECT".equals(statementType)) {
                // SELECT 分支：直接通过 jdbcTemplate 执行，返回结果数据（≤100 行由校验器 LIMIT 保证）
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sanitizedSql);
                // 防御性截断：确保返回数据不超过100行
                List<Map<String, Object>> data = rows.size() > MAX_RESULT_ROWS ? rows.subList(0, MAX_RESULT_ROWS) : rows;
                result.put("success", true);
                result.put("executed", true);
                result.put("rowCount", rows.size());
                result.put("data", data);
                // 指示 AI 用自然语言呈现数据，禁止提及或展示 SQL
                result.put("message", "查询已执行，请根据查询结果用自然语言向用户呈现数据，不要提及或展示 SQL 语句");
                log.info("SELECT直接执行完成: question={}, rowCount={}", question, rows.size());
            } else {
                // WRITE 分支：INSERT/UPDATE/DELETE 需用户确认后执行，创建 PENDING 待确认操作（24h 过期）
                ChatPendingAction action = new ChatPendingAction();
                action.setConversationId(UserContextHolder.getConversationId());
                action.setUserId(UserContextHolder.getUserId());
                action.setActionType(ACTION_TYPE_KNOWLEDGE); // 知识库/数据类操作
                action.setTargetType(TextToSqlService.TARGET_TYPE_SQL_QUERY);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("sql", sanitizedSql);
                payload.put("question", question);
                payload.put("statementType", statementType);
                action.setPayloadJson(objectMapper.writeValueAsString(payload));
                action.setPreviewText(question);
                ChatPendingAction saved = pendingActionService.createAction(action, PENDING_EXPIRE_HOURS);

                // 构建返回结果：告知 AI 已生成待确认操作，需询问用户是否执行
                result.put("success", true);
                result.put("pendingConfirmation", true);
                result.put("pendingActionId", saved.getId());
                result.put("question", question);
                // 指示 AI 询问用户是否执行，可回复 确认/取消，禁止展示 SQL
                result.put("message", "已生成数据变更操作，请询问用户是否确认执行，可回复 确认/取消，禁止向用户展示 SQL 语句");
                log.info("WRITE待确认操作已创建: pendingActionId={}, statementType={}, question={}", saved.getId(), statementType, question);
            }

            // 注意：两个分支均不得在返回 map 中放入 sql 键，防止前端/AI 直接暴露 SQL

        } catch (Exception e) {
            log.error("Text to SQL 处理失败: question={}", question, e);
            result.put("success", false);
            result.put("message", "查询处理失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 降级兜底：当主 LLM 生成的 SQL 校验失败时可回退调用此方法重新生成。
     * <p>
     * 主路径（{@link com.hagent.ai.tool.TextToSqlTool#queryBusinessData(String)} executor）不再经过此方法，
     * 直接接收主 LLM 生成的 SQL，仅经 {@link SqlSecurityValidator} 双通道校验后执行；
     * 本方法仅作为校验失败时的可选降级路径保留，确保双通道校验仍为唯一放行口。
     * </p>
     *
     * @param question 自然语言问题
     * @return 生成的 SQL 或错误信息
     */
    private String generateSql(String question) {
        String schemaDescription = schemaProvider.getSchemaDescription();
        String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, schemaDescription);

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(question));

        try {
            ChatModel chatModel = aiModelProvider.getChatModel();
            // 尝试真流式获取 thinking：优先使用 chatModel.stream() 增量获取 thinking delta 并立即发射
            // 若 streaming API 可用，thinking 将在流式回调中通过 extractThinking 提取并以 THINKING_DELAY 分散发射
            // 此处 generateSql 为同步工具调用，保持 call() 返回 SQL，同时记录 thinking 供后续流式包装使用
            ChatResponse response = chatModel.call(new Prompt(messages));
            // 提取思考内容（兼容 reasoning_content / thinking 等键），若存在则可通过 thinkingFluxWithDelay 延迟分散发射
            String thinking = extractThinking(response);
            if (thinking != null && !thinking.isEmpty()) {
                // thinking 采用 30 字符分片 + 30ms 间隔的模拟流式，确保前端时间分散；真流式场景由 streamGenerateSql 透传 delta
                log.debug("TextToSql 捕获思考内容: length={}, 将通过延迟分片模拟流式 (chunk={}, delay={})", thinking.length(), THINKING_CHUNK_SIZE, THINKING_DELAY);
                // 注意：同步的 generateSql 不直接发射 SSE，若上层需要流式展示，可调用 streamGenerateSqlThinking(question) 获取 Flux<thinking>
            }
            AssistantMessage assistantMessage = response.getResult().getOutput();
            String sql = assistantMessage.getText().trim();

            // 清理 SQL（去除可能的 markdown 代码块标记）
            sql = cleanSql(sql);

            return sql;
        } catch (Exception e) {
            log.error("AI生成SQL失败", e);
            return "ERROR: AI服务异常，请稍后重试";
        }
    }

    /**
     * 真流式生成 SQL 的思考流（供上层 Flux 包装使用）
     * <p>
     * 优先使用 chatModel.stream() 获取增量 thinking delta 并通过 wrapThinking 立即发射；
     * 若 streaming API 不可用或模型未返回增量 thinking，则回退为 30 字符分片 + 30ms 延迟发射的模拟流式，
     * 确保前端收到的 thinking 事件在时间上分散，而非一次性批量到达。
     * </p>
     *
     * @param question 自然语言问题
     * @return thinking 事件的 Flux 流，每项为 {"type":"thinking","content":"..."} JSON 字符串
     * @author H
     */
    public Flux<String> streamGenerateSqlThinking(String question) {
        String schemaDescription = schemaProvider.getSchemaDescription();
        String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, schemaDescription);
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(question));
        ChatModel chatModel = aiModelProvider.getChatModel();
        // 优先尝试真流式：订阅 chatModel.stream() 的每个 ChatResponse 增量，提取 thinking delta 立即发射
        try {
            Flux<ChatResponse> stream = chatModel.stream(new Prompt(messages));
            // 使用 Sinks.Many 承接增量 thinking，确保每个 delta 立即经 wrapThinking 发射
            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
            stream.subscribe(
                    response -> {
                        String delta = extractThinking(response);
                        if (delta != null && !delta.isEmpty()) {
                            if (delta.length() > 50) {
                                // 大块增量按 30 字符切分并以 THINKING_DELAY 延迟分散发射，避免前端爆发
                                List<String> pieces = splitThinking(delta, THINKING_CHUNK_SIZE);
                                Flux.fromIterable(pieces)
                                        .delayElements(THINKING_DELAY)
                                        .subscribe(piece -> sink.tryEmitNext(wrapThinking(piece)));
                            } else {
                                // 小增量真流式立即发射
                                sink.tryEmitNext(wrapThinking(delta));
                            }
                        }
                    },
                    err -> {
                        log.warn("TextToSql 流式 thinking 获取异常，回退为批量分片延迟发射", err);
                        // 异常回退：尝试 call() 获取完整 thinking 再分片延迟发射
                        fallbackThinkingFlux(question, sink);
                    },
                    () -> sink.tryEmitComplete()
            );
            return sink.asFlux();
        } catch (Exception e) {
            log.warn("chatModel.stream() 不可用，回退为 30 字符分片 + 30ms 延迟的模拟流式", e);
            // 回退：同步 call() 获取完整 thinking，按 30 字符分片后以 Flux.interval 风格延迟发射
            return fallbackThinkingFluxSync(question);
        }
    }

    /**
     * 回退路径：同步 call() 获取完整 thinking，按 30 字符分片并以 THINKING_DELAY 延迟发射
     *
     * @param question 自然语言问题
     * @param sink     目标 sink（用于异步回退场景）
     * @author H
     */
    private void fallbackThinkingFlux(String question, Sinks.Many<String> sink) {
        try {
            ChatModel chatModel = aiModelProvider.getChatModel();
            String schemaDescription = schemaProvider.getSchemaDescription();
            String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, schemaDescription);
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemPrompt));
            messages.add(new UserMessage(question));
            ChatResponse response = chatModel.call(new Prompt(messages));
            String thinking = extractThinking(response);
            if (thinking != null && !thinking.isEmpty()) {
                Flux<String> delayed = thinkingFluxWithDelay(thinking);
                delayed.subscribe(
                        chunk -> sink.tryEmitNext(chunk),
                        err -> sink.tryEmitError(err),
                        () -> sink.tryEmitComplete()
                );
            } else {
                sink.tryEmitComplete();
            }
        } catch (Exception ex) {
            sink.tryEmitError(ex);
        }
    }

    /**
     * 同步回退的 Flux（无 sink 场景）
     *
     * @param question 自然语言问题
     * @return 延迟分片的 thinking Flux
     * @author H
     */
    private Flux<String> fallbackThinkingFluxSync(String question) {
        try {
            ChatModel chatModel = aiModelProvider.getChatModel();
            String schemaDescription = schemaProvider.getSchemaDescription();
            String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, schemaDescription);
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemPrompt));
            messages.add(new UserMessage(question));
            ChatResponse response = chatModel.call(new Prompt(messages));
            String thinking = extractThinking(response);
            if (thinking == null || thinking.isEmpty()) {
                return Flux.empty();
            }
            // 保留 30 字符分片，但通过 Flux.delayElements(30ms) 使每个 chunk 在时间上分散
            return thinkingFluxWithDelay(thinking);
        } catch (Exception e) {
            log.error("回退 thinking 流失败", e);
            return Flux.empty();
        }
    }

    /**
     * 清理 AI 返回的 SQL
     * 去除 markdown 代码块标记等
     *
     * @param sql 原始 SQL
     * @return 清理后的 SQL
     */
    private String cleanSql(String sql) {
        // 去除 ```sql 和 ``` 标记（使用字面量替换，避免正则元字符问题）
        sql = sql.replace("```sql", "").replace("```", "");
        // 去除首尾空白
        sql = sql.trim();
        // 去除末尾分号
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        return sql;
    }

    /**
     * 将阶段进度包装为结构化JSON字符串
     * 与 ChatServiceImpl.wrapStage 保持一致，前端据 type=stage 字段展示阶段进度
     * 每个阶段对应 Flux SSE 事件：{"type":"stage","content":"<阶段文案>"}
     *
     * @param content 阶段文案（如“正在生成查询...”）
     * @return 结构化JSON字符串，格式：{"type":"stage","content":"<阶段文案>"}
     */
    private String wrapStage(String content) {
        try {
            return "{\"type\":\"stage\",\"content\":" + objectMapper.writeValueAsString(content) + "}";
        } catch (JacksonException e) {
            log.warn("JSON编码stage失败，降级为空内容", e);
            return "{\"type\":\"stage\",\"content\":\"\"}";
        }
    }

    /**
     * 将思考/推理增量包装为结构化JSON字符串
     * 前端据 type=thinking 字段区分并以折叠/流式方式展示思考过程
     *
     * @param thinkingChunk 思考内容增量（可能为完整块或 delta）
     * @return 结构化JSON字符串，格式：{"type":"thinking","content":"<思考增量>"}
     * @author H
     */
    private String wrapThinking(String thinkingChunk) {
        try {
            return "{\"type\":\"thinking\",\"content\":" + objectMapper.writeValueAsString(thinkingChunk) + "}";
        } catch (JacksonException e) {
            log.warn("JSON编码thinking失败，降级为空内容", e);
            return "{\"type\":\"thinking\",\"content\":\"\"}";
        }
    }

    /**
     * 从 ChatResponse 中提取思考/推理内容
     * 兼容 reasoning_content / reasoningContent / thinking / reasoning 等键
     * 真流式场景下每次调用返回增量 delta，需立即 via wrapThinking 发射；非流式场景返回完整块
     *
     * @param response ChatModel 返回的响应对象
     * @return 思考内容字符串，不存在时返回 null
     * @author H
     */
    private String extractThinking(ChatResponse response) {
        if (response == null) {
            return null;
        }
        if (response.getResult() != null && response.getResult().getOutput() != null) {
            AssistantMessage output = response.getResult().getOutput();
            Map<String, Object> meta = output.getMetadata();
            if (meta != null && !meta.isEmpty()) {
                String v = getThinkingFromMap(meta);
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
            var genMeta = response.getResult().getMetadata();
            if (genMeta != null && !genMeta.isEmpty()) {
                for (String key : List.of("reasoning_content", "reasoningContent", "reasoning", "thinking")) {
                    if (genMeta.containsKey(key)) {
                        Object obj = genMeta.get(key);
                        if (obj instanceof String s && !s.isBlank()) {
                            return s;
                        }
                    }
                }
            }
        }
        var respMeta = response.getMetadata();
        if (respMeta != null && !respMeta.isEmpty()) {
            for (String key : List.of("reasoning_content", "reasoningContent", "reasoning", "thinking")) {
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
     * @author H
     */
    private String getThinkingFromMap(Map<String, Object> meta) {
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
     * 将思考内容按固定大小切分为多段
     * 用于非流式回退的模拟流式：大块 thinking 按 30 字符切分后通过 Flux.delayElements 分散发射
     *
     * @param thinking  完整思考内容
     * @param chunkSize 切分大小
     * @return 切分后的片段列表
     * @author H
     */
    private List<String> splitThinking(String thinking, int chunkSize) {
        List<String> pieces = new ArrayList<>();
        for (int start = 0; start < thinking.length(); start += chunkSize) {
            int end = Math.min(start + chunkSize, thinking.length());
            pieces.add(thinking.substring(start, end));
        }
        return pieces;
    }

    /**
     * 创建带延迟的思考流
     * 将 30 字符分片后的 thinking 通过 Flux.delayElements 按 THINKING_DELAY 间隔发射，确保前端时间上分散
     * 若流式 API 可用则优先直接透传增量 delta；否则走此延迟分片兜底，避免批量一次性爆发
     *
     * @param thinking 完整思考内容（批量收集场景）
     * @return 延迟发射的 thinking 事件 Flux
     * @author H
     */
    private Flux<String> thinkingFluxWithDelay(String thinking) {
        if (thinking == null || thinking.isEmpty()) {
            return Flux.empty();
        }
        if (thinking.length() <= THINKING_CHUNK_SIZE) {
            // 小块直接延迟微小间隔后发射，保持时序分散
            return Flux.just(wrapThinking(thinking)).delayElements(THINKING_DELAY);
        }
        List<String> pieces = splitThinking(thinking, THINKING_CHUNK_SIZE);
        // 按 THINKING_DELAY 间隔延迟发射每个分片，保证前端收到 thinking 事件分散在时间轴上
        return Flux.fromIterable(pieces)
                .delayElements(THINKING_DELAY)
                .map(this::wrapThinking);
    }
}
