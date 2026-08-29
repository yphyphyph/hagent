package com.hagent.ai.core;

import com.alibaba.druid.DbType;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 数据库表结构提供者（PostgreSQL）
 * 读取 pg_catalog 元数据，生成 Schema 描述供 AI 理解，方言为 {@link DbType#postgresql}
 *
 * @author H
 */
@Slf4j
@Component
public class DatabaseSchemaProvider {

    /** 方言标记：PostgreSQL（与 SqlSecurityValidator 保持一致） */
    @SuppressWarnings("unused")
    private static final DbType DB_TYPE = DbType.postgresql;

    private final JdbcTemplate jdbcTemplate;

    /** 允许查询的表白名单（PostgreSQL，不含 visit_stat 与 user 表，防止读取敏感信息） */
    private static final List<String> ALLOWED_TABLES = List.of(
            "chat_conversation", "chat_message", "knowledge_file", "ai_prompt"
    );

    /** 表结构缓存（兼容旧字段，实际以内存缓存为准） */
    private volatile String schemaDescription;

    /** 内存缓存：基于 ConcurrentHashMap 的 Schema 缓存 */
    private final ConcurrentHashMap<String, String> schemaCache = new ConcurrentHashMap<>();

    /** 缓存 Key */
    private static final String CACHE_KEY = "schemaDescription";

    /** 缓存 TTL：5 分钟（毫秒） */
    private static final long CACHE_TTL_MILLIS = 5 * 60 * 1000L;

    /** 最后更新时间戳（毫秒），volatile 保证可见性 */
    private volatile long lastUpdateTimestamp = 0L;

    public DatabaseSchemaProvider(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        refreshSchema();
    }

    /**
     * 刷新 Schema 缓存
     * 主动刷新时同步更新 ConcurrentHashMap 缓存与时间戳
     */
    public void refreshSchema() {
        try {
            List<TableInfo> tables = loadTableInfos();
            String description = buildSchemaDescription(tables);
            this.schemaDescription = description;
            // 更新内存缓存与时间戳
            schemaCache.put(CACHE_KEY, description);
            lastUpdateTimestamp = System.currentTimeMillis();
            log.info("数据库Schema已加载，表数量: {}，缓存已刷新，TTL={}ms", tables.size(), CACHE_TTL_MILLIS);
        } catch (Exception e) {
            log.error("加载数据库Schema失败", e);
            this.schemaDescription = "Schema加载失败";
            // 失败时也写入缓存，避免频繁重试穿透数据库；设置时间戳以便 TTL 后重试
            schemaCache.put(CACHE_KEY, this.schemaDescription);
            lastUpdateTimestamp = System.currentTimeMillis();
        }
    }

    /**
     * 获取 Schema 描述文本（带 5 分钟 TTL 内存缓存）
     * <p>
     * 缓存命中时直接返回内存数据，不查询 information_schema；
     * 缓存未命中或过期时同步刷新并返回最新数据。
     * </p>
     *
     * @return Schema 描述字符串
     */
    public String getSchemaDescription() {
        long now = System.currentTimeMillis();
        String cached = schemaCache.get(CACHE_KEY);
        // 缓存命中且未过期：直接返回
        if (cached != null && (now - lastUpdateTimestamp) < CACHE_TTL_MILLIS) {
            long remaining = CACHE_TTL_MILLIS - (now - lastUpdateTimestamp);
            log.info("Schema 缓存命中，直接返回缓存数据，剩余 TTL: {}ms", remaining);
            log.debug("Schema cache hit, remaining TTL={}ms", remaining);
            return cached;
        }

        // 缓存未命中或已过期
        if (cached == null) {
            log.info("Schema 缓存未命中（首次加载或已被清除），准备查询 pg_catalog");
        } else {
            log.info("Schema 缓存已过期（距上次更新 {}ms，TTL={}ms），重新加载", now - lastUpdateTimestamp, CACHE_TTL_MILLIS);
        }
        log.debug("Schema cache miss, querying pg_catalog");

        // 同步刷新，避免并发下多次查询 pg_catalog
        synchronized (this) {
            // 双重检查：进入同步块后再次判断，避免并发重复刷新
            long nowInner = System.currentTimeMillis();
            String cachedInner = schemaCache.get(CACHE_KEY);
            if (cachedInner != null && (nowInner - lastUpdateTimestamp) < CACHE_TTL_MILLIS) {
                long remainingInner = CACHE_TTL_MILLIS - (nowInner - lastUpdateTimestamp);
                log.info("Schema 缓存命中（同步块内双重检查），剩余 TTL: {}ms", remainingInner);
                return cachedInner;
            }
            // 确实需要刷新
            refreshSchema();
            String result = schemaCache.get(CACHE_KEY);
            // 兜底：若缓存仍为空则返回旧字段
            return result != null ? result : schemaDescription;
        }
    }

    /**
     * 清除 Schema 缓存（用于测试或管理接口手动失效）
     */
    public void clearCache() {
        schemaCache.remove(CACHE_KEY);
        lastUpdateTimestamp = 0L;
        log.info("Schema 缓存已手动清除");
    }

    /**
     * 判断缓存是否过期（供外部监控或扩展使用）
     */
    public boolean isCacheExpired() {
        return schemaCache.get(CACHE_KEY) == null
                || (System.currentTimeMillis() - lastUpdateTimestamp) >= CACHE_TTL_MILLIS;
    }

    /**
     * 从数据库加载表结构信息
     */
    private List<TableInfo> loadTableInfos() {
        List<TableInfo> tables = new ArrayList<>();

        for (String tableName : ALLOWED_TABLES) {
            try {
                TableInfo tableInfo = new TableInfo();
                tableInfo.setTableName(tableName);

                // 获取表注释
                String tableComment = getTableComment(tableName);
                tableInfo.setTableComment(tableComment);

                // 获取列信息
                List<ColumnInfo> columns = getColumnInfo(tableName);
                tableInfo.setColumns(columns);

                tables.add(tableInfo);
            } catch (Exception e) {
                log.warn("获取表结构失败: {}", tableName, e);
            }
        }

        return tables;
    }

    /**
     * 获取表注释（PostgreSQL：obj_description）
     */
    private String getTableComment(String tableName) {
        try {
            // PostgreSQL 表注释：obj_description('表名'::regclass)，需用 regclass 解析当前 search_path 下的表
            String sql = "SELECT obj_description(?::regclass)";
            String comment = jdbcTemplate.queryForObject(sql, String.class, tableName);
            return comment != null ? comment : tableName;
        } catch (Exception e) {
            return tableName;
        }
    }

    /**
     * 获取列信息（PostgreSQL：pg_catalog + col_description + pg_index 主键判定）
     * 类型取 data_type/udt_name 语义（此处用 format_type 展开含长度精度），注释用 col_description
     */
    private List<ColumnInfo> getColumnInfo(String tableName) {
        // PostgreSQL 列信息：pg_attribute + pg_class + col_description + pg_index 主键判定，类型用 format_type（等价 data_type/udt_name 展开）
        String sql = """
                SELECT
                    a.attname AS "COLUMN_NAME",
                    format_type(a.atttypid, a.atttypmod) AS "COLUMN_TYPE",
                    col_description(c.oid, a.attnum) AS "COLUMN_COMMENT",
                    CASE WHEN a.attnotnull THEN 'NO' ELSE 'YES' END AS "IS_NULLABLE",
                    CASE WHEN EXISTS (
                        SELECT 1 FROM pg_index i
                        WHERE i.indrelid = c.oid
                          AND i.indisprimary
                          AND a.attnum = ANY(i.indkey)
                    ) THEN 'PRI' ELSE '' END AS "COLUMN_KEY"
                FROM pg_class c
                JOIN pg_attribute a ON a.attrelid = c.oid
                WHERE c.relname = ?
                  AND c.relkind IN ('r','p')
                  AND a.attnum > 0
                  AND NOT a.attisdropped
                ORDER BY a.attnum
                """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, tableName);

        return rows.stream().map(row -> {
            ColumnInfo col = new ColumnInfo();
            col.setColumnName((String) row.get("COLUMN_NAME"));
            col.setColumnType((String) row.get("COLUMN_TYPE"));
            col.setColumnComment((String) row.get("COLUMN_COMMENT"));
            col.setNullable("YES".equals(row.get("IS_NULLABLE")));
            col.setPrimaryKey("PRI".equals(row.get("COLUMN_KEY")));
            return col;
        }).collect(Collectors.toList());
    }

    /**
     * 构建 Schema 描述文本
     */
    private String buildSchemaDescription(List<TableInfo> tables) {
        StringBuilder sb = new StringBuilder();
        sb.append("数据库表结构如下：\n\n");

        for (TableInfo table : tables) {
            sb.append("表名: ").append(table.getTableName());
            if (table.getTableComment() != null && !table.getTableComment().isEmpty()) {
                sb.append(" (").append(table.getTableComment()).append(")");
            }
            sb.append("\n");
            sb.append("字段:\n");

            for (ColumnInfo col : table.getColumns()) {
                sb.append("  - ").append(col.getColumnName())
                        .append(" ").append(col.getColumnType());
                if (col.getColumnComment() != null && !col.getColumnComment().isEmpty()) {
                    sb.append(" -- ").append(col.getColumnComment());
                }
                if (col.isPrimaryKey()) {
                    sb.append(" [主键]");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // 添加常用查询示例（PostgreSQL 语法）
        sb.append("常用查询示例（PostgreSQL）：\n");
        sb.append("- 统计AI会话数: SELECT COUNT(*) FROM chat_conversation WHERE deleted = 0\n");
        sb.append("- 按日期统计会话: SELECT DATE_TRUNC('day', created_at) as date, COUNT(*) as count FROM chat_conversation WHERE deleted = 0 GROUP BY DATE_TRUNC('day', created_at)\n");
        sb.append("- 今日会话: SELECT COUNT(*) FROM chat_conversation WHERE created_at >= CURRENT_DATE AND deleted = 0\n");
        sb.append("- 查询知识库文件: SELECT * FROM knowledge_file WHERE deleted = 0 ORDER BY created_at DESC LIMIT 100\n");
        sb.append("- 按状态统计知识库文件: SELECT status, COUNT(*) as count FROM knowledge_file WHERE deleted = 0 GROUP BY status\n");
        sb.append("- 查询提示词: SELECT * FROM ai_prompt WHERE deleted = 0 AND status = 1 LIMIT 100\n");

        return sb.toString();
    }

    @Data
    public static class TableInfo {
        private String tableName;
        private String tableComment;
        private List<ColumnInfo> columns;
    }

    @Data
    public static class ColumnInfo {
        private String columnName;
        private String columnType;
        private String columnComment;
        private boolean nullable;
        private boolean primaryKey;
    }
}
