package com.hagent.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 知识库文件查询请求DTO
 * 支持筛选和RAG搜索查询
 *
 * @author H
 */
@Data
@Schema(description = "知识库文件查询请求")
public class KnowledgeQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文件名筛选 */
    @Size(max = 100, message = "文件名不能超过100字符")
    @Schema(description = "文件名筛选", example = "产品手册")
    private String fileName;

    /** 文件扩展名筛选 */
    @Schema(description = "文件扩展名筛选", example = "pdf")
    private String fileExt;

    /** 向量状态筛选 */
    @Schema(description = "向量状态筛选：1=待处理 2=处理中 3=已完成 4=失败", example = "3")
    private Integer vectorStatus;

    /** RAG搜索查询文本 */
    @Size(max = 200, message = "查询内容不能超过200字符")
    @Schema(description = "RAG搜索查询文本", example = "如何使用知识库")
    private String query;

    /** 页码 */
    @Min(value = 1, message = "页码不能小于1")
    @Schema(description = "页码", example = "1")
    private Integer pageNum = 1;

    /** 每页大小 */
    @Min(value = 1, message = "每页条数不能小于1")
    @Max(value = 100, message = "每页条数不能超过100")
    @Schema(description = "每页大小", example = "10")
    private Integer pageSize = 10;
}
