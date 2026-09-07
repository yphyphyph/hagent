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
     * @param query  搜索查询文本
     * @param topK   返回的Top结果数量
     * @param fileId 知识库文件ID（可选，精确检索指定知识库文件时传入；为空时全域检索）
     * @return 匹配的文档文本列表
     */
    @Tool(description = "按查询文本搜索知识库文档，返回匹配的内容片段。适用于检索文档内容、要点、细节；数量统计/排名类问题请改用 queryBusinessData")
    public List<String> searchKnowledge(
            @ToolParam(description = "搜索查询文本") String query,
            @ToolParam(description = "返回的Top结果数量，默认5") int topK,
            @ToolParam(required = false, description = "精确检索指定知识库文件时传入的知识库文件ID（用户消息 [文档：name|id] 标记中的 id），为空时全域检索") Long fileId) {
        // 校验 topK 范围，防止恶意传入过大值导致查询性能问题
        if (topK <= 0) {
            topK = DEFAULT_TOP_K;
        }
        if (topK > MAX_TOP_K) {
            topK = MAX_TOP_K;
        }
        log.info("AI工具 - searchKnowledge: query={}, topK={}, fileId={}", query, topK, fileId);
        // 可见性过滤：公有文件全员可检索，私有文件仅上传人可检索；
        // fileId 非空时进入精确模式（检索层仅向量通道按 file_id 锁定），本工具仅透传不复制过滤逻辑
        List<Document> docs = ragService.searchAccessible(query, topK, 0.25, fileId);
        return docs.stream()
                .map(Document::getText)
                .collect(Collectors.toList());
    }
}
