package com.hagent.ai.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * AI 智能工具执行请求
 *
 * @author H
 */
@Data
@Schema(description = "AI 智能工具执行请求")
public class AiToolRequest {

    /** 工具标识：writing/seo/rag_qa/text2sql/report */
    @Schema(description = "工具标识：writing/seo/rag_qa/text2sql/report", example = "writing")
    private String toolKey;

    /** 工具入参（不同工具约定不同字段，如 topic/style/length/content/question 等） */
    @Schema(description = "工具入参（不同工具约定不同字段，如 topic/style/length/content/question 等）")
    private Map<String, Object> params;

    /** 可选：自定义系统提示词覆盖 */
    @Schema(description = "可选：自定义系统提示词覆盖")
    private String promptOverride;

    /** 可选：RAG 问答的文件ID筛选 */
    @Schema(description = "可选：RAG 问答的文件ID筛选")
    private List<String> fileIds;
}
