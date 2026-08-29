package com.hagent.ai.service;

import com.hagent.ai.model.entity.SqlTablePolicy;

import java.util.List;

/**
 * Text-to-SQL 表白名单策略服务
 *
 * @author H
 */
public interface SqlTablePolicyService {

    /**
     * 查询策略列表（含停用）
     *
     * @return 策略列表
     */
    List<SqlTablePolicy> listPolicies();

    /**
     * 新增策略（保存前校验表在当前数据库中真实存在）
     *
     * @param policy 策略
     * @return 保存后的策略
     */
    SqlTablePolicy createPolicy(SqlTablePolicy policy);

    /**
     * 更新策略（表存在性同样校验）
     *
     * @param id     策略ID
     * @param policy 策略
     * @return 更新后的策略
     */
    SqlTablePolicy updatePolicy(Long id, SqlTablePolicy policy);

    /**
     * 删除策略
     *
     * @param id 策略ID
     */
    void deletePolicy(Long id);
}
