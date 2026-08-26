package com.hagent.ai.tool;

import com.hagent.ai.core.SqlSecurityValidator;
import com.hagent.ai.core.UserContextHolder;
import com.hagent.ai.model.entity.ChatPendingAction;
import com.hagent.ai.service.PendingActionService;
import com.hagent.ai.service.TextToSqlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Text to SQL 工具 - 执行器模式
 * 仅做安全校验与执行，不负责 SQL 生成
 *
 * @author H
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TextToSqlTool {

    private final TextToSqlService textToSqlService;
    private final SqlSecurityValidator sqlValidator;
    private final JdbcTemplate jdbcTemplate;
    private final PendingActionService pendingActionService;
    private final ObjectMapper objectMapper;

    /**
     * 执行由主 LLM 生成的 SQL，仅做安全校验与执行
     * SELECT 直接执行返回数据，INSERT/UPDATE/DELETE 需用户回复确认后执行
     *
     * @param sql 主 LLM 生成的 SQL 语句，需符合 Schema，仅单条 SELECT/INSERT/UPDATE/DELETE
     * @return 执行结果
     * @author H
     */
    @Tool(description = "执行由主 LLM 生成的 SQL，仅做安全校验与执行。SELECT 直接执行返回数据，INSERT/UPDATE/DELETE 需用户回复确认后执行。禁止展示 SQL。")
    public Map<String, Object> queryBusinessData(
            @ToolParam(description = "主 LLM 生成的 SQL 语句，需符合 Schema，仅单条 SELECT/INSERT/UPDATE/DELETE") String sql) {
        log.info("AI工具 - queryBusinessData [executor]: sql={}", sql);

        try {
            // 安全校验
            SqlSecurityValidator.ValidationResult v = sqlValidator.validate(sql);
            if (!v.isValid()) {
                log.warn("SQL安全校验失败: sql={}, message={}", sql, v.getMessage());
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("success", false);
                fail.put("message", v.getMessage());
                return fail;
            }

            String sanitized = v.getSanitizedSql();

            if ("SELECT".equals(v.getStatementType())) {
                // SELECT 分支：直接执行
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sanitized);
                List<Map<String, Object>> data = rows.size() > 100 ? rows.subList(0, 100) : rows;
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", true);
                result.put("executed", true);
                result.put("rowCount", rows.size());
                result.put("data", data);
                result.put("message", "查询已执行，请根据查询结果用自然语言向用户呈现数据，不要提及或展示 SQL 语句");
                log.info("SELECT直接执行完成: rowCount={}", rows.size());
                return result;
            } else {
                // WRITE 分支：INSERT/UPDATE/DELETE 需确认，创建待确认操作
                ChatPendingAction action = new ChatPendingAction();
                action.setConversationId(UserContextHolder.getConversationId());
                action.setUserId(UserContextHolder.getUserId());
                action.setActionType(3);
                action.setTargetType(TextToSqlService.TARGET_TYPE_SQL_QUERY);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("sql", sanitized);
                payload.put("question", sql);
                action.setPayloadJson(objectMapper.writeValueAsString(payload));
                action.setPreviewText(sql);
                ChatPendingAction saved = pendingActionService.createAction(action, 24);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", true);
                result.put("pendingConfirmation", true);
                result.put("pendingActionId", saved.getId());
                result.put("message", "写操作已暂存，请询问用户是否确认执行，可回复 确认/取消，禁止向用户展示 SQL 语句");
                log.info("WRITE待确认操作已创建: pendingActionId={}, statementType={}", saved.getId(), v.getStatementType());
                return result;
            }
        } catch (Exception e) {
            log.error("TextToSqlTool executor执行失败: sql={}", sql, e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("message", "查询处理失败: " + e.getMessage());
            return error;
        }
    }
}
