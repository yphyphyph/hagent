package com.hagent.ai.mcp;

import com.hagent.ai.tool.TextToSqlTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * MCP服务器配置
 * 通过MCP协议暴露知识库、数据分析、图片识别和Text-to-SQL工具
 * （博客搜索工具已随博客模块下线移除）
 *
 * @author H
 */
@Slf4j
@Configuration
@Profile("!test")
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider mcpToolCallbackProvider(
            KnowledgeSearchTool knowledgeSearchTool,
            DataAnalysisTool dataAnalysisTool,
            AnalyzeImageTool analyzeImageTool,
            TextToSqlTool textToSqlTool) {
        log.info("注册MCP工具: KnowledgeSearchTool, DataAnalysisTool, AnalyzeImageTool, TextToSqlTool");
        return MethodToolCallbackProvider.builder()
                .toolObjects(knowledgeSearchTool, dataAnalysisTool, analyzeImageTool, textToSqlTool)
                .build();
    }
}
