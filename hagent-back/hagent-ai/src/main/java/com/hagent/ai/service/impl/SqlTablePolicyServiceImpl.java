package com.hagent.ai.service.impl;

import com.hagent.ai.core.SqlTablePolicyHolder;
import com.hagent.ai.mapper.SqlTablePolicyMapper;
import com.hagent.ai.model.entity.SqlTablePolicy;
import com.hagent.ai.service.SqlTablePolicyService;
import com.hagent.common.annotation.OperationLog;
import com.hagent.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Text-to-SQL 表白名单策略服务实现
 * <p>
 * 表名仅允许字母数字下划线（防注入到 information_schema 查询）；
 * 保存/更新前校验表在当前数据库中真实存在；任何变更后刷新缓存供校验器即时生效。
 *
 * @author H
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlTablePolicyServiceImpl implements SqlTablePolicyService {

    /** 合法表名（校验目标表与隔离列名共用，仅字母数字下划线，杜绝引号/空白注入） */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");

    /** 隔离列名最大长度 */
    private static final int USER_COLUMN_MAX_LENGTH = 64;

    private final SqlTablePolicyMapper sqlTablePolicyMapper;
    private final SqlTablePolicyHolder sqlTablePolicyHolder;

    @Override
    public List<SqlTablePolicy> listPolicies() {
        return sqlTablePolicyMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SqlTablePolicy>()
                        .orderByAsc(SqlTablePolicy::getTableName));
    }

    @Override
    @OperationLog(module = "sql", operationType = "CREATE", description = "新增SQL表策略", saveParams = true, saveResult = false)
    public SqlTablePolicy createPolicy(SqlTablePolicy policy) {
        validateIdentifier(policy);
        if (sqlTablePolicyMapper.countTableExists(policy.getTableName()) <= 0) {
            throw new BusinessException("表 " + policy.getTableName() + " 在当前数据库中不存在");
        }
        policy.setId(null);
        try {
            sqlTablePolicyMapper.insert(policy);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException("表 " + policy.getTableName() + " 已在白名单中");
        }
        sqlTablePolicyHolder.refresh();
        log.info("新增Text-to-SQL表策略: table={}, userScoped={}, visibilityAware={}",
                policy.getTableName(), policy.getUserScoped(), policy.getVisibilityAware());
        return policy;
    }

    @Override
    @OperationLog(module = "sql", operationType = "UPDATE", description = "更新SQL表策略", saveParams = true, saveResult = false)
    public SqlTablePolicy updatePolicy(Long id, SqlTablePolicy policy) {
        SqlTablePolicy exists = sqlTablePolicyMapper.selectById(id);
        if (exists == null) {
            throw new BusinessException("策略不存在");
        }
        validateIdentifier(policy);
        if (sqlTablePolicyMapper.countTableExists(policy.getTableName()) <= 0) {
            throw new BusinessException("表 " + policy.getTableName() + " 在当前数据库中不存在");
        }
        policy.setId(id);
        try {
            sqlTablePolicyMapper.updateById(policy);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException("表 " + policy.getTableName() + " 已在白名单中");
        }
        sqlTablePolicyHolder.refresh();
        log.info("更新Text-to-SQL表策略: id={}, table={}", id, policy.getTableName());
        return sqlTablePolicyMapper.selectById(id);
    }

    @Override
    @OperationLog(module = "sql", operationType = "DELETE", description = "删除SQL表策略", saveParams = true, saveResult = false)
    public void deletePolicy(Long id) {
        SqlTablePolicy exists = sqlTablePolicyMapper.selectById(id);
        if (exists == null) {
            throw new BusinessException("策略不存在");
        }
        sqlTablePolicyMapper.deleteById(id);
        sqlTablePolicyHolder.refresh();
        log.info("删除Text-to-SQL表策略: id={}, table={}", id, exists.getTableName());
    }

    /**
     * 校验表名与隔离列名为合法标识符（仅字母数字下划线，长度受控）
     *
     * @param policy 策略
     */
    private void validateIdentifier(SqlTablePolicy policy) {
        if (policy == null || policy.getTableName() == null
                || !IDENTIFIER_PATTERN.matcher(policy.getTableName()).matches()
                || policy.getTableName().length() > USER_COLUMN_MAX_LENGTH) {
            throw new BusinessException("表名不合法（仅允许字母数字下划线）");
        }
        if (policy.getUserColumn() == null || policy.getUserColumn().isBlank()) {
            policy.setUserColumn("user_id");
        }
        if (!IDENTIFIER_PATTERN.matcher(policy.getUserColumn()).matches()
                || policy.getUserColumn().length() > USER_COLUMN_MAX_LENGTH) {
            throw new BusinessException("隔离列名不合法（仅允许字母数字下划线）");
        }
        // 归一化默认值：未显式指定时按启用与按用户隔离处理
        if (policy.getUserScoped() == null) {
            policy.setUserScoped(1);
        }
        if (policy.getVisibilityAware() == null) {
            policy.setVisibilityAware(0);
        }
        if (policy.getEnabled() == null) {
            policy.setEnabled(1);
        }
    }
}
