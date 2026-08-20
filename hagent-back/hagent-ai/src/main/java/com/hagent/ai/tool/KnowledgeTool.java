package com.hagent.ai.tool;

import com.hagent.ai.core.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库搜索工具
 * 以Spring AI @Tool暴露，搜索RAG向量存储供Agent使用
 *
 * @author H
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeTool {

    private final RagService ragService;

    /** 默认 TopK 数量 */
    private static final int DEFAULT_TOP_K = 5;
    /** 最大 TopK 数量 */
    private static final int MAX_TOP_K = 20;

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
        // 校验 topK 范围，防止恶意传入过大值导致查询性能问题
        if (topK <= 0) {
            topK = DEFAULT_TOP_K;
        }
        if (topK > MAX_TOP_K) {
            topK = MAX_TOP_K;
        }
        log.info("AI工具 - searchKnowledge: query={}, topK={}", query, topK);
        List<Document> docs = ragService.search(query, topK, 0.25);
        return docs.stream()
                .map(Document::getText)
                .collect(Collectors.toList());
    }
}
