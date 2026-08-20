package com.hagent.ai.core;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据库表结构提供者
 * 读取数据库元数据，生成 Schema 描述供 AI 理解
 *
 * @author H
 */
@Slf4j
@Component
public class DatabaseSchemaProvider {

    private final JdbcTemplate jdbcTemplate;

    /** 允许查询的表白名单（不含 user 表，防止读取密码哈希等敏感信息） */
    private static final List<String> ALLOWED_TABLES = List.of(
            "chat_conversation", "chat_message", "knowledge_file", "ai_prompt", "visit_stat"
    );

    /** 表结构缓存 */
    private String schemaDescription;

    public DatabaseSchemaProvider(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        refreshSchema();
    }

    /**
     * 刷新 Schema 缓存
     */
    public void refreshSchema() {
        try {
            List<TableInfo> tables = loadTableInfos();
            this.schemaDescription = buildSchemaDescription(tables);
            log.info("数据库Schema已加载，表数量: {}", tables.size());
        } catch (Exception e) {
            log.error("加载数据库Schema失败", e);
            this.schemaDescription = "Schema加载失败";
        }
    }

    /**
     * 获取 Schema 描述文本
     */
    public String getSchemaDescription() {
        return schemaDescription;
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
     * 获取表注释
     */
    private String getTableComment(String tableName) {
        try {
            String sql = """
                    SELECT TABLE_COMMENT
                    FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME = ?
                    """;
            return jdbcTemplate.queryForObject(sql, String.class, tableName);
        } catch (Exception e) {
            return tableName;
        }
    }

    /**
     * 获取列信息
     */
    private List<ColumnInfo> getColumnInfo(String tableName) {
        String sql = """
                SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT, IS_NULLABLE, COLUMN_KEY
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = ?
                ORDER BY ORDINAL_POSITION
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

        // 添加常用查询示例
        sb.append("常用查询示例：\n");
        sb.append("- 统计AI会话数: SELECT COUNT(*) FROM chat_conversation WHERE deleted = 0\n");
        sb.append("- 按日期统计会话: SELECT DATE(created_at) as date, COUNT(*) as count FROM chat_conversation WHERE deleted = 0 GROUP BY DATE(created_at)\n");
        sb.append("- 查询知识库文件: SELECT * FROM knowledge_file WHERE deleted = 0 ORDER BY created_at DESC LIMIT N\n");
        sb.append("- 按状态统计知识库文件: SELECT status, COUNT(*) as count FROM knowledge_file WHERE deleted = 0 GROUP BY status\n");
        sb.append("- 查询提示词: SELECT * FROM ai_prompt WHERE deleted = 0 AND status = 1\n");

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
