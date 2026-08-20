package com.hagent.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统配置实体，对应表 system_config
 */
@Data
@TableName("system_config")
public class SystemConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 配置ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置键 */
    private String configKey;

    /** 配置值 */
    private String configValue;

    /** 配置类型(system/ai/website/social) */
    private String configType;

    /** 描述 */
    private String description;

    /** 是否公开(0=否 1=是) */
    private Integer isPublic;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 创建人 */
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 更新人 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    /** 逻辑删除(0-未删除 1-已删除) */
    @TableLogic
    private Integer deleted;
}
