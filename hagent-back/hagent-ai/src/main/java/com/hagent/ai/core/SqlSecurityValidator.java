package com.hagent.ai.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 安全校验器
 * 防止 SQL 注入和危险操作
 *
 * @author H
 */
@Slf4j
@Component
public class SqlSecurityValidator {

    /** 禁止的 SQL 关键字 */
    private static final List<String> FORBIDDEN_KEYWORDS = List.of(
            "DELETE", "DROP", "TRUNCATE", "ALTER", "INSERT", "UPDATE",
            "CREATE", "REPLACE", "RENAME", "GRANT", "REVOKE",
            "UNION", "OUTFILE", "DUMPFILE", "LOAD_FILE", "SLEEP", "BENCHMARK",
            "INFORMATION_SCHEMA", "MYSQL", "PROCEDURE", "CALL", "HANDLER",
            "LOCK", "FLUSH", "RESET", "SHUTDOWN", "KILL"
    );

    /** 禁止关键字组合正则（预编译以提升性能，使用单词边界避免误匹配） */
    private static final Pattern FORBIDDEN_PATTERN =
            Pattern.compile("\\b(" + String.join("|", FORBIDDEN_KEYWORDS) + ")\\b");

    /** 敏感列黑名单（防止读取密码、手机号、邮箱、身份证等隐私字段） */
    private static final List<String> SENSITIVE_COLUMNS = List.of(
            "password", "passwd", "pwd", "phone", "mobile", "email", "mail",
            "id_card", "idcard", "id_number", "secret", "token", "credential",
            "api_key", "apikey", "private_key", "access_key", "accesskey"
    );

    /** 敏感列检测正则（单词边界匹配，大小写不敏感） */
    private static final Pattern SENSITIVE_COLUMN_PATTERN = Pattern.compile(
            "\\b(" + String.join("|", SENSITIVE_COLUMNS) + ")\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** 多语句分隔符 */
    private static final Pattern MULTI_STATEMENT_PATTERN = Pattern.compile(";\\s*\\S");

    /** LIMIT 行数提取正则（大小写不敏感，兼容 LIMIT n 与 LIMIT offset, count 两种形式） */
    private static final Pattern LIMIT_PATTERN =
            Pattern.compile("LIMIT\\s+(\\d+)\\s*(?:,\\s*(\\d+))?", Pattern.CASE_INSENSITIVE);

    /** 默认最大返回行数 */
    private static final int DEFAULT_MAX_ROWS = 100;

    /**
     * 校验 SQL 安全性
     *
     * @param sql 待校验的 SQL
     * @return 校验结果
     */
    public ValidationResult validate(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return ValidationResult.fail("SQL不能为空");
        }

        // 去除多余空白并转大写
        String normalizedSql = sql.trim().replaceAll("\\s+", " ").toUpperCase();

        // 1. 必须以 SELECT 开头
        if (!normalizedSql.startsWith("SELECT")) {
            return ValidationResult.fail("只允许 SELECT 查询语句");
        }

        // 2. 检查禁止的关键字（使用预编译组合正则，单词边界匹配）
        Matcher forbiddenMatcher = FORBIDDEN_PATTERN.matcher(normalizedSql);
        if (forbiddenMatcher.find()) {
            return ValidationResult.fail("禁止使用 " + forbiddenMatcher.group(1) + " 操作");
        }

        // 3. 检查敏感列（防止读取密码、手机号、邮箱、身份证等隐私字段）
        Matcher sensitiveMatcher = SENSITIVE_COLUMN_PATTERN.matcher(sql);
        if (sensitiveMatcher.find()) {
            log.warn("检测到敏感列查询，已拦截: column={}", sensitiveMatcher.group(1));
            return ValidationResult.fail("禁止查询敏感字段: " + sensitiveMatcher.group(1));
        }

        // 4. 检查多语句
        if (MULTI_STATEMENT_PATTERN.matcher(sql.trim()).find()) {
            return ValidationResult.fail("禁止执行多条SQL语句");
        }

        // 5. 检查注释（防止绕过检测）：-- 行注释、/* */ 块注释、# MySQL 行注释
        if (sql.contains("--") || sql.contains("/*") || sql.contains("#")) {
            return ValidationResult.fail("SQL中禁止使用注释");
        }

        // 6. 校验并修正 LIMIT 行数，确保不超过最大行数限制
        String finalSql = ensureLimitClause(sql.trim());

        return ValidationResult.success(finalSql);
    }

    /**
     * 确保 SQL 包含 LIMIT 子句且行数不超过最大限制
     * 若存在 LIMIT 且超过 DEFAULT_MAX_ROWS，则替换为 DEFAULT_MAX_ROWS；若无 LIMIT 则追加
     *
     * @param sql 原始 SQL
     * @return 处理后的 SQL
     */
    private String ensureLimitClause(String sql) {
        Matcher matcher = LIMIT_PATTERN.matcher(sql);
        if (matcher.find()) {
            // 形式二：LIMIT offset, count（group2 为实际行数）；形式一：LIMIT count（group1 为行数）
            boolean hasOffset = matcher.group(2) != null;
            int rows = hasOffset ? Integer.parseInt(matcher.group(2)) : Integer.parseInt(matcher.group(1));
            // 超过最大行数则重写为最大行数，防止返回过多数据
            if (rows > DEFAULT_MAX_ROWS) {
                String offsetPrefix = hasOffset ? matcher.group(1) + ", " : "";
                return matcher.replaceFirst("LIMIT " + offsetPrefix + DEFAULT_MAX_ROWS);
            }
            return sql;
        }
        // 无 LIMIT 则追加默认行数限制
        return sql + " LIMIT " + DEFAULT_MAX_ROWS;
    }

    /**
     * 校验结果
     */
    @lombok.Data
    public static class ValidationResult {
        private boolean valid;
        private String message;
        private String sanitizedSql;

        /**
         * 构建成功结果
         *
         * @param sanitizedSql 校验通过后的 SQL
         * @return 成功结果
         */
        public static ValidationResult success(String sanitizedSql) {
            ValidationResult result = new ValidationResult();
            result.setValid(true);
            result.setSanitizedSql(sanitizedSql);
            return result;
        }

        /**
         * 构建失败结果
         *
         * @param message 失败信息
         * @return 失败结果
         */
        public static ValidationResult fail(String message) {
            ValidationResult result = new ValidationResult();
            result.setValid(false);
            result.setMessage(message);
            return result;
        }
    }
}
