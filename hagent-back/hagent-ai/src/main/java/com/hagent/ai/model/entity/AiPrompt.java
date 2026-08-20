package com.hagent.ai.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hagent.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 提示词库实体，对应表 ai_prompt
 *
 * @author H
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_prompt")
public class AiPrompt extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 标题 */
    private String title;

    /** 分类(writing/seo/analysis/general 等) */
    private String category;

    /** 提示词内容(支持 {占位符}) */
    private String content;

    /** 描述 */
    private String description;

    /** 变量占位符列表(逗号分隔) */
    private String variables;

    /** 是否内置(0=否 1=是,内置不可删除) */
    private Integer builtin;

    /** 使用次数 */
    private Integer useCount;

    /** 排序 */
    private Integer sortOrder;

    /** 状态(1=启用 0=禁用) */
    private Integer status;
}
