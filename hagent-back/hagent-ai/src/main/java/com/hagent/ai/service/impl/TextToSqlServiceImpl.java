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
import java.util.HashMap;
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

    /** 系统提示词模板 */
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是一个 SQL 专家。根据用户的自然语言问题，生成对应的 MySQL 查询语句。

            %s

            规则：
            1. 只生成 SELECT 语句
            2. 所有表都有 deleted 字段，查询时必须添加 WHERE deleted = 0 条件
            3. 使用标准 MySQL 语法
            4. 只返回 SQL 语句，不要包含任何解释
            5. 如果问题无法用 SQL 查询，返回 "ERROR: 无法回答此问题"
            6. 字段名使用下划线命名法（如 order_no、created_at）
            7. 日期函数使用 MySQL 标准函数（如 NOW()、DATE()、YEAR()、MONTH()）
            8. 涉及金额的字段使用 DECIMAL 类型，保留2位小数
            9. 对于统计查询，使用合适的聚合函数（SUM、COUNT、AVG、MAX、MIN）
            10. 对于排序，默认使用 DESC 降序
            """;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> queryByNaturalLanguage(String question) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // 1. 调用 AI 生成 SQL
            String sql = generateSql(question);
            log.info("AI生成的SQL: {}", sql);

            // 检查是否是错误响应
            if (sql.startsWith("ERROR:")) {
                result.put("success", false);
                result.put("message", sql.substring(6).trim());
                return result;
            }

            // 2. 安全校验
            SqlSecurityValidator.ValidationResult validation = sqlValidator.validate(sql);
            if (!validation.isValid()) {
                result.put("success", false);
                result.put("message", "SQL安全校验失败: " + validation.getMessage());
                return result;
            }

            String sanitizedSql = validation.getSanitizedSql();
            log.info("校验后的SQL: {}", sanitizedSql);

            // 3. 创建待确认操作（不直接执行，由用户在确认卡片上确认后执行）
            ChatPendingAction action = new ChatPendingAction();
            action.setConversationId(UserContextHolder.getConversationId());
            action.setUserId(UserContextHolder.getUserId());
            action.setActionType(3); // 知识库/数据类操作
            action.setTargetType(TextToSqlService.TARGET_TYPE_SQL_QUERY);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sql", sanitizedSql);
            payload.put("question", question);
            action.setPayloadJson(objectMapper.writeValueAsString(payload));
            action.setPreviewText(question);
            ChatPendingAction saved = pendingActionService.createAction(action, 24);

            // 4. 构建返回结果：告知AI已生成SQL等待确认
            result.put("success", true);
            result.put("pendingConfirmation", true);
            result.put("pendingActionId", saved.getId());
            result.put("sql", sanitizedSql);
            result.put("question", question);
            result.put("message", "SQL已生成并通过安全校验，等待用户确认后执行");

            log.info("SQL待确认操作已创建: pendingActionId={}, question={}", saved.getId(), question);

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
