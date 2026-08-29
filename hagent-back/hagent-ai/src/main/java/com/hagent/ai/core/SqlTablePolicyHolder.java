package com.hagent.ai.core;

import com.hagent.ai.mapper.SqlTablePolicyMapper;
import com.hagent.ai.model.entity.SqlTablePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Text-to-SQL 表白名单策略缓存
 * <p>
 * SqlSecurityValidator 在每次校验时高频读取策略，直查数据库会成为瓶颈，
 * 这里以 volatile 快照缓存启用的策略列表，管理端变更后调用 {@link #refresh()} 刷新。
 * 策略表不可用或为空时回退为空白名单（fail-closed：拒绝一切查询）。
 *
 * @author H
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class SqlTablePolicyHolder {

    private final SqlTablePolicyMapper sqlTablePolicyMapper;

    /** 启用中的策略快照（tableName 小写 → 策略），volatile 保证跨线程可见 */
    private volatile Map<String, SqlTablePolicy> enabledPolicies = Map.of();

    /** 是否至少有一条启用策略（空白名单时校验器 fail-closed） */
    private volatile boolean anyPolicyEnabled = false;

    /**
     * 刷新缓存：从策略表加载启用中的策略并重建快照
     */
    public synchronized void refresh() {
        try {
            List<SqlTablePolicy> policies = sqlTablePolicyMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SqlTablePolicy>()
                            .eq(SqlTablePolicy::getEnabled, 1));
            applySnapshot(policies);
        } catch (Exception e) {
            log.error("Text-to-SQL 表白名单加载失败，维持上次快照(fail-closed依赖快照为空时全拒)", e);
        }
    }

    /**
     * 应用策略快照（管理端变更与测试注入共用入口）
     *
     * @param policies 启用中的策略列表
     */
    public synchronized void applySnapshot(List<SqlTablePolicy> policies) {
        Map<String, SqlTablePolicy> snapshot = policies == null ? Map.of() : policies.stream()
                .filter(p -> p.getTableName() != null && !p.getTableName().isBlank()
                        && p.getUserColumn() != null && !p.getUserColumn().isBlank())
                .collect(Collectors.toMap(
                        p -> p.getTableName().toLowerCase(),
                        Function.identity(),
                        (a, b) -> a));
        this.enabledPolicies = snapshot;
        this.anyPolicyEnabled = !snapshot.isEmpty();
        log.info("Text-to-SQL 表白名单已刷新: enabledPolicies={}", snapshot.keySet());
    }

    /**
     * 初始化缓存
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        refresh();
    }

    /**
     * 按表名获取启用中的策略
     *
     * @param tableName 表名（不区分大小写）
     * @return 策略，不在白名单时返回 null
     */
    public SqlTablePolicy getEnabledPolicy(String tableName) {
        if (tableName == null) {
            return null;
        }
        return enabledPolicies.get(tableName.toLowerCase());
    }

    /**
     * 是否至少有一条启用策略（用于空白名单 fail-closed 判定）
     */
    public boolean isAnyPolicyEnabled() {
        return anyPolicyEnabled;
    }
}

