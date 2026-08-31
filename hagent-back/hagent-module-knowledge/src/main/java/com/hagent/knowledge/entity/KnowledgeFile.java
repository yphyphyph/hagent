package com.hagent.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hagent.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库文件实体，对应表 t_knowledge_file
 *
 * @author H
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_file")
public class KnowledgeFile extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 文件名 */
    private String fileName;

    /** 文件下载URL */
    private String fileUrl;

    /** OSS对象名称 */
    private String objectName;

    /** 文件大小（字节） */
    private Long fileSize;

    /** MIME类型 */
    private String fileType;

    /** 文件扩展名 */
    private String fileExt;

    /** 可见性(0=私有 1=公有)：权限仅存 PostgreSQL 关系库，检索侧原生 filterExpression 过滤，切换即时生效无需重新向量化 */
    private Integer visibility;

    /** 上传用户ID（私有文件的归属依据） */
    private Long userId;

    /** 向量状态：1=待处理，2=处理中，3=已完成，4=失败 */
    private Integer vectorStatus;

    /** 向量版本号 */
    private Integer vectorVersion;

    /** 文本分块数量 */
    private Integer chunkCount;

    /** Token总数 */
    private Integer tokenCount;

    /** 处理失败错误信息 */
    private String errorMessage;

    /** 向量化快照：embedding 模型 */
    private String embeddingModel;

    /** 向量化快照：向量维度 */
    private Integer embeddingDimension;
}
