package com.hagent.ai.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hagent.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Text-to-SQL 表白名单与行级隔离策略实体，对应表 ai_sql_table_policy
 * <p>
 * 白名单内的表才允许被 Text-to-SQL 查询；user_scoped 表强制注入
 * user_column = 当前用户 的行级隔离；visibility_aware 表对公有记录豁免隔离。
 * hagent_user 等敏感表不配置白名单即天然不可查。
 *
 * @author H
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_sql_table_policy")
public class SqlTablePolicy extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 受管控的表名（唯一） */
    private String tableName;

    /** 是否按用户隔离(0=否 1=是，强制注入 user_column=当前用户) */
    private Integer userScoped;

    /** 用户隔离列名 */
    private String userColumn;

    /** 是否支持公私(0=否 1=是，公有记录豁免用户隔离) */
    private Integer visibilityAware;

    /** 是否启用(0=停用 1=启用) */
    private Integer enabled;

    /** 备注 */
    private String remark;
}
