package com.hagent.ai.mcp;

import com.hagent.ai.core.RagService;
import com.hagent.ai.service.QueryRewriteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * MCP知识库搜索工具
 * 通过@McpTool暴露，搜索RAG向量存储供MCP协议访问
 * 检索前先经查询补全改写（同义映射 + 多轮指代补全），提升召回质量
 *
 * @author H
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeSearchTool {

    private final RagService ragService;

    private final QueryRewriteService queryRewriteService;

    /** 空结果提示语：明示调用方知识库无此内容，避免外部客户端盲目重试 */
    private static final String NO_HIT_HINT =
            "知识库中未找到与查询相关的内容，请基于你已掌握的知识回答或如实告知用户无此信息。";

    /**
     * 按查询搜索知识库文档
     *
     * @param query 搜索查询文本
     * @param topK  返回的Top结果数量
     * @return 匹配的文档文本列表
     */
    @Tool(description = "按查询文本搜索知识库文档")
    public List<String> searchKnowledge(
            @ToolParam(description = "搜索查询文本") String query,
            @ToolParam(description = "返回的Top结果数量，默认5") int topK) {
        log.info("MCP工具 - searchKnowledge: query={}, topK={}", query, topK);
        // 检索前查询补全改写：同义词映射 + 多轮指代补全（失败/无上下文时回退原查询）
        String rewrittenQuery = queryRewriteService.rewrite(query);
        // 可见性过滤：MCP 侧通常无用户身份，仅公有文件可检索
        List<Document> docs = ragService.searchAccessible(rewrittenQuery, topK, 0.25);
        if (docs.isEmpty()) {
            // 空结果明确告知调用方，避免外部客户端盲目重试
            return List.of(NO_HIT_HINT);
        }
        return docs.stream()
                .map(Document::getText)
                .collect(Collectors.toList());
    }
}
