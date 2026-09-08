package com.hagent.ai.tool;

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
 * 知识库搜索工具
 * 以Spring AI @Tool暴露，搜索RAG向量存储供Agent使用
 * 检索前先经查询补全改写（同义映射 + 多轮指代补全），提升召回质量
 *
 * @author H
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeTool {

    private final RagService ragService;

    private final QueryRewriteService queryRewriteService;

    /** 默认 TopK 数量 */
    private static final int DEFAULT_TOP_K = 5;
    /** 最大 TopK 数量 */
    private static final int MAX_TOP_K = 20;

    /** 空结果提示语：明示模型知识库无此内容并引导停止换词重试（前缀与 AgentToolLoopService.RAG_MISS_MARKER 保持一致） */
    private static final String NO_HIT_HINT =
            "知识库中未找到与查询相关的内容。请勿换词重复检索：若问题涉及你自身（如你是什么、能做什么、什么架构），请直接回答；"
                    + "否则基于你已掌握的知识回答，或如实告知用户知识库中无此信息。";

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
        // 检索前查询补全改写：同义词映射 + 多轮指代补全（失败/无上下文时回退原查询）
        String rewrittenQuery = queryRewriteService.rewrite(query);
        // 可见性过滤：公有文件全员可检索，私有文件仅上传人可检索；
        // fileId 非空时进入精确模式（检索层仅向量通道按 file_id 锁定），本工具仅透传不复制过滤逻辑
        List<Document> docs = ragService.searchAccessible(rewrittenQuery, topK, 0.25, fileId);
        if (docs.isEmpty()) {
            // 空结果明确告知模型（配合 AgentToolLoopService 连续未命中终止，防发散循环检索）
            return List.of(NO_HIT_HINT);
        }
        return docs.stream()
                .map(Document::getText)
                .collect(Collectors.toList());
    }
}
