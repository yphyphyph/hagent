package com.hagent.ai.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 安全校验器
 * 防止 SQL 注入和危险操作，支持 SELECT 直查与 INSERT/UPDATE/DELETE 受控写操作双通道
 *
 * @author H
 */
@Slf4j
@Component
public class SqlSecurityValidator {

    /** 禁止的 SQL 关键字（SELECT 通道：包含写关键字，禁止任何写操作） */
    private static final List<String> FORBIDDEN_KEYWORDS = List.of(
            "DELETE", "DROP", "TRUNCATE", "ALTER", "INSERT", "UPDATE",
            "CREATE", "REPLACE", "RENAME", "GRANT", "REVOKE",
            "UNION", "OUTFILE", "DUMPFILE", "LOAD_FILE", "SLEEP", "BENCHMARK",
            "INFORMATION_SCHEMA", "MYSQL", "PROCEDURE", "CALL", "HANDLER",
            "LOCK", "FLUSH", "RESET", "SHUTDOWN", "KILL"
    );

    /** 写通道禁止的 SQL 关键字（移除 DELETE/INSERT/UPDATE，其余与 SELECT 通道一致，禁止 DDL） */
    private static final List<String> WRITE_FORBIDDEN_KEYWORDS = List.of(
            "DROP", "TRUNCATE", "ALTER",
            "CREATE", "REPLACE", "RENAME", "GRANT", "REVOKE",
            "UNION", "OUTFILE", "DUMPFILE", "LOAD_FILE", "SLEEP", "BENCHMARK",
            "INFORMATION_SCHEMA", "MYSQL", "PROCEDURE", "CALL", "HANDLER",
            "LOCK", "FLUSH", "RESET", "SHUTDOWN", "KILL"
    );

    /** 禁止关键字组合正则（预编译以提升性能，使用单词边界避免误匹配） */
    private static final Pattern FORBIDDEN_PATTERN =
            Pattern.compile("\\b(" + String.join("|", FORBIDDEN_KEYWORDS) + ")\\b");

    /** 写通道禁止关键字正则（预编译，单词边界匹配） */
    private static final Pattern WRITE_FORBIDDEN_PATTERN =
            Pattern.compile("\\b(" + String.join("|", WRITE_FORBIDDEN_KEYWORDS) + ")\\b");

    /** 敏感列黑名单（防止读取密码、手机号、邮箱、身份证等隐私字段） */
    private static final List<String> SENSITIVE_COLUMNS = List.of(
            "password", "passwd", "pwd", "phone", "mobile", "email", "mail",
            "id_card", "idcard", "id_number", "secret", "token", "credential",
            "api_key", "apikey", "private_key", "access_key", "accesskey"
    );

    /** 敏感列检测正则（支持前后缀匹配，如 password_hash，大小写不敏感） */
    private static final Pattern SENSITIVE_COLUMN_PATTERN = Pattern.compile(
            "\\b\\w*(" + String.join("|", SENSITIVE_COLUMNS) + ")\\w*\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** 多语句分隔符 */
    private static final Pattern MULTI_STATEMENT_PATTERN = Pattern.compile(";\\s*\\S");

    /** LIMIT 行数提取正则（大小写不敏感，兼容 LIMIT n 与 LIMIT offset, count 两种形式） */
    private static final Pattern LIMIT_PATTERN =
            Pattern.compile("LIMIT\\s+(\\d+)\\s*(?:,\\s*(\\d+))?", Pattern.CASE_INSENSITIVE);

    /** 默认最大返回行数 */
    private static final int DEFAULT_MAX_ROWS = 100;

    /** WHERE 关键字检测正则（用于 UPDATE/DELETE 强制 WHERE 校验，大小写不敏感） */
    private static final Pattern WHERE_PATTERN = Pattern.compile("\\bWHERE\\b", Pattern.CASE_INSENSITIVE);

    /** 语句类型常量：SELECT 查询 */
    private static final String STATEMENT_TYPE_SELECT = "SELECT";

    /** 语句类型常量：WRITE 写操作（INSERT/UPDATE/DELETE） */
    private static final String STATEMENT_TYPE_WRITE = "WRITE";

    /**
     * 校验 SQL 安全性（双通道路由）
     * 根据首关键字路由到 SELECT 或 WRITE 校验通道
     *
     * @param sql 待校验的 SQL
     * @return 校验结果，包含 statementType（SELECT/WRITE）
     * @author H
     */
    public ValidationResult validate(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return ValidationResult.fail("SQL不能为空");
        }

        // 提取首个关键字（大小写不敏感），用于路由校验通道
        String trimmed = sql.trim();
        String firstKeyword = trimmed.replaceAll("\\s+", " ").split(" ")[0].toUpperCase();

        switch (firstKeyword) {
            case "SELECT":
                // SELECT 通道：维持原有 6 项严格校验
                return validateSelect(sql);
            case "INSERT":
            case "UPDATE":
            case "DELETE":
                // WRITE 通道：受控放行写操作
                return validateWrite(sql);
            default:
                // 非允许类型一律拒绝
                return ValidationResult.fail("只允许SELECT/INSERT/UPDATE/DELETE");
        }
    }

    /**
     * SELECT 通道校验（维持原有 6 项检查：SELECT 前缀、禁用关键字、敏感列、多语句、注释、LIMIT 强制）
     *
     * @param sql 待校验的 SQL
     * @return 校验结果，成功时 statementType 为 SELECT
     * @author H
     */
    private ValidationResult validateSelect(String sql) {
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

        // 2.1 禁止 SELECT *（防止敏感列通过通配符泄露，fail-closed）
        if (normalizedSql.matches("(?s).*SELECT\\s+\\*.*")) {
            return ValidationResult.fail("禁止使用 SELECT *，请明确列名");
        }

        // 3. 检查敏感列（支持前后缀匹配，如 password_hash，大小写不敏感）
        Matcher sensitiveMatcher = SENSITIVE_COLUMN_PATTERN.matcher(sql);
        if (sensitiveMatcher.find()) {
            log.warn("检测到敏感列查询，已拦截: column={}", sensitiveMatcher.group(1));
            return ValidationResult.fail("禁止查询敏感字段: " + sensitiveMatcher.group(1));
        }

        // 4. 检查多语句（单语句限制，禁止分号后跟内容）
        if (MULTI_STATEMENT_PATTERN.matcher(sql.trim()).find()) {
            return ValidationResult.fail("禁止执行多条SQL语句");
        }

        // 5. 检查注释（防止绕过检测）：-- 行注释、/* */ 块注释、# MySQL 行注释
        if (sql.contains("--") || sql.contains("/*") || sql.contains("#")) {
            return ValidationResult.fail("SQL中禁止使用注释");
        }

        // 6. 校验并修正 LIMIT 行数，确保不超过最大行数限制
        String finalSql = ensureLimitClause(sql.trim());

        // 构建成功结果并标注语句类型为 SELECT
        ValidationResult result = ValidationResult.success(finalSql);
        result.setStatementType(STATEMENT_TYPE_SELECT);
        return result;
    }

    /**
     * WRITE 通道校验（INSERT/UPDATE/DELETE 受控放行）
     * 保留 DDL 禁止、单语句、注释、敏感列检查；UPDATE/DELETE 强制 WHERE 与 LIMIT 100
     *
     * @param sql 待校验的 SQL
     * @return 校验结果，成功时 statementType 为 WRITE
     * @author H
     */
    private ValidationResult validateWrite(String sql) {
        // 归一化 SQL 用于关键字检测（大小写不敏感）
        String normalizedSql = sql.trim().replaceAll("\\s+", " ").toUpperCase();
        // 提取首关键字用于区分 INSERT 与 UPDATE/DELETE 的差异化校验
        String firstKeyword = normalizedSql.split(" ")[0];

        // 1. 检查写通道禁用关键字（不含 DELETE/INSERT/UPDATE，但仍禁止 DDL 等危险操作）
        Matcher forbiddenMatcher = WRITE_FORBIDDEN_PATTERN.matcher(normalizedSql);
        if (forbiddenMatcher.find()) {
            return ValidationResult.fail("禁止使用 " + forbiddenMatcher.group(1) + " 操作");
        }

        // 2. 检查敏感列（写操作同样禁止操作隐私字段）
        Matcher sensitiveMatcher = SENSITIVE_COLUMN_PATTERN.matcher(sql);
        if (sensitiveMatcher.find()) {
            log.warn("检测到敏感列操作，已拦截: column={}", sensitiveMatcher.group(1));
            return ValidationResult.fail("禁止操作敏感字段: " + sensitiveMatcher.group(1));
        }

        // 3. 检查多语句（单语句限制）
        if (MULTI_STATEMENT_PATTERN.matcher(sql.trim()).find()) {
            return ValidationResult.fail("禁止执行多条SQL语句");
        }

        // 4. 检查注释（防止绕过检测）
        if (sql.contains("--") || sql.contains("/*") || sql.contains("#")) {
            return ValidationResult.fail("SQL中禁止使用注释");
        }

        // 5. UPDATE/DELETE 必须包含 WHERE 条件，防止全表误操作（先剥离字符串字面量避免 'where' 误判）
        if ("UPDATE".equals(firstKeyword) || "DELETE".equals(firstKeyword)) {
            // 去除单引号与双引号字符串字面量后再检测 WHERE，避免字面量中的 where 绕过校验
            String stripped = sql.replaceAll("'[^']*'", "''").replaceAll("\"[^\"]*\"", "\"\"");
            if (!WHERE_PATTERN.matcher(stripped).find()) {
                return ValidationResult.fail("修改操作必须带 WHERE 条件");
            }
        }

        // 6. UPDATE/DELETE 强制 LIMIT 100（复用 ensureLimitClause），INSERT 跳过
        String finalSql = sql.trim();
        if ("UPDATE".equals(firstKeyword) || "DELETE".equals(firstKeyword)) {
            finalSql = ensureLimitClause(finalSql);
        }

        // 构建成功结果并标注语句类型为 WRITE
        ValidationResult result = ValidationResult.success(finalSql);
        result.setStatementType(STATEMENT_TYPE_WRITE);
        return result;
    }

    /**
     * 确保 SQL 包含 LIMIT 子句且行数不超过最大限制
     * 遍历所有 LIMIT 子句，逐个检查并超限截断；若无 LIMIT 则追加默认限制
     *
     * @param sql 原始 SQL
     * @return 处理后的 SQL
     * @author H
     */
    private String ensureLimitClause(String sql) {
        Matcher matcher = LIMIT_PATTERN.matcher(sql);
        boolean found = false;
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            found = true;
            // 形式二：LIMIT offset, count（group2 为实际行数）；形式一：LIMIT count（group1 为行数）
            boolean hasOffset = matcher.group(2) != null;
            int rows = hasOffset ? Integer.parseInt(matcher.group(2)) : Integer.parseInt(matcher.group(1));
            if (rows > DEFAULT_MAX_ROWS) {
                // 超过最大行数则重写为最大行数，防止返回过多数据
                String offsetPrefix = hasOffset ? matcher.group(1) + ", " : "";
                matcher.appendReplacement(sb, Matcher.quoteReplacement("LIMIT " + offsetPrefix + DEFAULT_MAX_ROWS));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        if (found) {
            matcher.appendTail(sb);
            return sb.toString();
        }
        // 无 LIMIT 则追加默认行数限制
        return sql + " LIMIT " + DEFAULT_MAX_ROWS;
    }

    /**
     * 校验结果
     *
     * @author H
     */
    @lombok.Data
    public static class ValidationResult {
        /** 是否校验通过 */
        private boolean valid;
        /** 失败时的错误信息 */
        private String message;
        /** 校验通过后的 SQL（已做 LIMIT 等安全修正） */
        private String sanitizedSql;
        /** 语句类型：SELECT 或 WRITE */
        private String statementType;

        /**
         * 构建成功结果
         *
         * @param sanitizedSql 校验通过后的 SQL
         * @return 成功结果
         * @author H
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
         * @author H
         */
        public static ValidationResult fail(String message) {
            ValidationResult result = new ValidationResult();
            result.setValid(false);
            result.setMessage(message);
            return result;
        }
    }
}
