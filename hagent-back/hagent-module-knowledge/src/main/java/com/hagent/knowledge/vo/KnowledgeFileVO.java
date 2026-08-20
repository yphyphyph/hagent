package com.hagent.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库文件API响应VO
 * 向API层暴露选定字段
 *
 * @author H
 */
@Data
@Schema(description = "知识库文件VO")
public class KnowledgeFileVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文件ID */
    @Schema(description = "文件ID", example = "1")
    private Long id;

    /** 文件名 */
    @Schema(description = "文件名", example = "产品手册.pdf")
    private String fileName;

    /** 文件下载URL */
    @Schema(description = "文件下载URL")
    private String fileUrl;

    /** OSS对象名称 */
    @Schema(description = "OSS对象名称")
    private String objectName;

    /** 文件大小（字节） */
    @Schema(description = "文件大小（字节）", example = "102400")
    private Long fileSize;

    /** MIME类型 */
    @Schema(description = "MIME类型", example = "application/pdf")
    private String fileType;

    /** 文件扩展名 */
    @Schema(description = "文件扩展名", example = "pdf")
    private String fileExt;

    /** 向量状态：1=待处理，2=处理中，3=已完成，4=失败 */
    @Schema(description = "向量状态：1=待处理 2=处理中 3=已完成 4=失败", example = "3")
    private Integer vectorStatus;

    /** 向量状态描述 */
    @Schema(description = "向量状态描述", example = "已完成")
    private String vectorStatusDesc;

    /** 向量版本号 */
    @Schema(description = "向量版本号", example = "1")
    private Integer vectorVersion;

    /** 文本分块数量 */
    @Schema(description = "文本分块数量", example = "12")
    private Integer chunkCount;

    /** Token总数 */
    @Schema(description = "Token总数", example = "3000")
    private Integer tokenCount;

    /** 处理失败错误信息 */
    @Schema(description = "处理失败错误信息")
    private String errorMessage;

    /** 创建时间戳 */
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    /** 创建人 */
    @Schema(description = "创建人")
    private String createdBy;
}
