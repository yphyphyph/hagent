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

    /**
     * 系统提示词模板：允许 SELECT/INSERT/UPDATE/DELETE，受控生成单条语句
     * 提示词中的 LIMIT 100 对应 {@link #MAX_RESULT_ROWS}，与校验器 {@code SqlSecurityValidator#DEFAULT_MAX_ROWS} 保持一致
     */
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是一个 SQL 专家。根据用户的自然语言问题，生成对应的 MySQL 语句.

            %s

            规则：
            1. 只允许生成单条 SELECT / INSERT / UPDATE / DELETE 语句，禁止生成 DDL/DCL（DROP/TRUNCATE/ALTER/CREATE等）、多语句或注释
            2. SELECT 查询必须添加 WHERE deleted = 0 条件，且必须包含 LIMIT 子句（最多100行）；若用户未指定数量则默认 LIMIT 100
            3. UPDATE / DELETE 必须包含 WHERE 条件，禁止全表操作，且必须包含 LIMIT 100
            4. 使用标准 MySQL 语法，字段名使用下划线命名法（如 order_no、created_at），日期函数使用 MySQL 标准函数（NOW()、DATE()、YEAR()、MONTH()）
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
     * 调用 AI 生成 SQL
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
            ChatResponse response = chatModel.call(new Prompt(messages));
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
}
