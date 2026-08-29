package com.hagent.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hagent.ai.model.entity.SqlTablePolicy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Text-to-SQL 表白名单策略 Mapper
 *
 * @author H
 */
@Mapper
public interface SqlTablePolicyMapper extends BaseMapper<SqlTablePolicy> {

    /**
     * 校验表在当前数据库中是否存在（PostgreSQL：CURRENT_SCHEMA()）
     *
     * @param tableName 表名
     * @return 存在的表数量（0=不存在 1=存在）
     */
    @Select("SELECT COUNT(*) FROM information_schema.tables "
            + "WHERE table_schema = CURRENT_SCHEMA() AND table_name = #{tableName}")
    int countTableExists(@Param("tableName") String tableName);
}
