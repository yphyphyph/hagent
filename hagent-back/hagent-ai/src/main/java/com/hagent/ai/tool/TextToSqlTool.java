package com.hagent.ai.tool;

import com.hagent.ai.service.TextToSqlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Text to SQL 工具
 * 将自然语言查询转换为 SQL 并执行
 *
 * @author H
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TextToSqlTool {

    private final TextToSqlService textToSqlService;

    /**
     * 通过自然语言查询业务数据
     *
     * @param question 用户的自然语言查询问题
     * @return 查询结果
     */
    @Tool(description = """
            通过自然语言查询业务数据（先确认后执行）。调用后系统会生成 SQL 并等待用户在确认卡片上确认，
            用户确认后才会真正执行并返回数据。适用于数据库中已有数据的统计与查询：
            知识库文件、文章、用户、订单等表的数量统计、排名、聚合分析。
            典型问题：
            - 知识库里有多少个文件？文章总数是多少？
            - 本月销售额最高的前5个订单是什么？
            - 最近7天每天的订单数量？
            - 哪个客户的订单金额最大？
            - 浏览量最高的文章有哪些？
            注意：检索文档的具体内容/要点请改用 searchKnowledge；统计数量、排名、聚合请用本工具。
            """)
    public Map<String, Object> queryBusinessData(
            @ToolParam(description = "用户的自然语言查询问题") String question) {
        log.info("AI工具 - queryBusinessData: question={}", question);

        Map<String, Object> result = textToSqlService.queryByNaturalLanguage(question);

        // 简化返回结果，避免 token 过多
        if (Boolean.TRUE.equals(result.get("success"))) {
            Map<String, Object> simplified = new java.util.LinkedHashMap<>();
            simplified.put("question", result.get("question"));
            simplified.put("pendingConfirmation", true);
            simplified.put("pendingActionId", result.get("pendingActionId"));
            simplified.put("sql", result.get("sql"));
            simplified.put("message", result.get("message"));
            return simplified;
        } else {
            return result;
        }
    }
}
