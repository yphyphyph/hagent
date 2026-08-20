package com.hagent.ai.mcp;

import com.hagent.ai.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP数据分析工具
 * 通过@Tool暴露，供MCP协议与AI Agent调用
 * （原博客文章统计已随博客模块下线，改为 AI 会话与知识库统计）
 *
 * @author H
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataAnalysisTool {

    private final ConversationService conversationService;

    /**
     * 分析平台运营统计（AI 会话、知识库等）
     *
     * @return 平台统计数据
     */
    @Tool(description = "分析平台运营统计数据（AI会话、知识库等）")
    public Map<String, Object> analyzePlatform() {
        log.info("MCP工具 - analyzePlatform");
        Map<String, Object> result = new HashMap<>();
        try {
            long conversationCount = conversationService.count();
            result.put("totalConversations", conversationCount);
        } catch (Exception e) {
            log.warn("会话统计失败: {}", e.getMessage());
            result.put("totalConversations", 0L);
        }
        return result;
    }
}
