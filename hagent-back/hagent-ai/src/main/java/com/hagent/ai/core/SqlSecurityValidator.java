package com.hagent.ai.core;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLLimit;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOperator;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQuery;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLSubqueryTableSource;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.dialect.postgresql.parser.PGSQLStatementParser;
import com.alibaba.druid.sql.dialect.postgresql.visitor.PGSchemaStatVisitor;
import com.alibaba.druid.stat.TableStat;
import com.hagent.ai.model.entity.SqlTablePolicy;
import com.hagent.common.context.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 安全校验器（Druid AST 版 / PostgreSQL 方言）
 * <p>
 * 防线设计（全部 fail-closed）：
 * 1. Druid 解析：仅接受单条标准 PostgreSQL 语句，解析失败即拒绝（多语句/注释注入/方言滥用无处遁形）；
 * 2. 白名单：语句涉及的全部表（含子查询/JOIN）必须在 {@link SqlTablePolicyHolder} 启用策略内；
 * 3. 行级隔离：user_scoped 表强制注入 user_column = 当前用户；visibility_aware 表对公有记录豁免；
 *    谓词在 AST 层按查询块注入，子查询同样覆盖；与白名单联动；
 * 4. 结构校验：SELECT 禁 UNION/SELECT *，写通道禁 INSERT...SELECT，UPDATE/DELETE 强制 WHERE（不追加 LIMIT，PG 原生不支持）；
 * 5. 文本兜底：剥离字符串字面量后做禁用关键字与敏感列检查（防 COPY/PG_SLEEP/DBLINK 等函数型逃逸）。
 * <p>
 * 对两条执行路径同时生效：聊天工具 TextToSqlTool 与工具市场 TextToSqlServiceImpl.queryByNaturalLanguage。
 *
 * @author H
 */
@Slf4j
@Component
public class SqlSecurityValidator {

    /** SELECT 通道禁用关键字（PostgreSQL 口径：剥离字面量后单词边界匹配，AST 之外的兜底防线） */
    private static final Pattern SELECT_FORBIDDEN_PATTERN = Pattern.compile(
            "\\b(DELETE|DROP|TRUNCATE|ALTER|INSERT|UPDATE|CREATE|REPLACE|RENAME|GRANT|REVOKE"
                    + "|COPY|DO|DBLINK|PG_SLEEP|PG_READ_FILE|PG_LS_DIR|LO_IMPORT|LO_EXPORT"
                    + "|PROCEDURE|CALL|HANDLER|LOCK|SHUTDOWN|KILL)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 写通道禁用关键字（PostgreSQL 口径：允许 INSERT/UPDATE/DELETE，禁止 DDL 与危险函数） */
    private static final Pattern WRITE_FORBIDDEN_PATTERN = Pattern.compile(
            "\\b(DROP|TRUNCATE|ALTER|CREATE|REPLACE|RENAME|GRANT|REVOKE"
                    + "|COPY|DO|DBLINK|PG_SLEEP|PG_READ_FILE|PG_LS_DIR|LO_IMPORT|LO_EXPORT"
                    + "|PROCEDURE|CALL|HANDLER|LOCK|SHUTDOWN|KILL)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 敏感列黑名单（防读取密码、手机号、邮箱、身份证等隐私字段，支持前后缀匹配） */
    private static final List<String> SENSITIVE_COLUMNS = List.of(
            "password", "passwd", "pwd", "phone", "mobile", "email", "mail",
            "id_card", "idcard", "id_number", "secret", "token", "credential",
            "api_key", "apikey", "private_key", "access_key", "accesskey");

    /** 敏感列检测正则（大小写不敏感） */
    private static final Pattern SENSITIVE_COLUMN_PATTERN = Pattern.compile(
            "\\b\\w*(" + String.join("|", SENSITIVE_COLUMNS) + ")\\w*\\b",
            Pattern.CASE_INSENSITIVE);

    /** 默认最大返回/影响行数 */
    private static final int DEFAULT_MAX_ROWS = 100;

    /** 语句类型常量：SELECT 查询 */
    private static final String STATEMENT_TYPE_SELECT = "SELECT";

    /** 语句类型常量：WRITE 写操作（INSERT/UPDATE/DELETE） */
    private static final String STATEMENT_TYPE_WRITE = "WRITE";

    private final SqlTablePolicyHolder policyHolder;

    public SqlSecurityValidator(SqlTablePolicyHolder policyHolder) {
        this.policyHolder = policyHolder;
    }

    /**
     * 校验 SQL 安全性（双通道路由）
     * 根据语句类型路由到 SELECT 或 WRITE 校验通道，全部基于 Druid AST 判定
     *
     * @param sql 待校验的 SQL
     * @return 校验结果，包含 statementType（SELECT/WRITE）与 sanitizedSql（AST 还原的安全 SQL）
     * @author H
     */
    public ValidationResult validate(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return ValidationResult.fail("SQL不能为空");
        }

        // 1. 文本兜底检查（先剥离字符串字面量，消除 'a;b--c' 类误杀）
        String stripped = stripStringLiterals(sql);
        if (sql.contains("/*")) {
            // 禁止块注释：PostgreSQL 亦可能滥用注释规避校验，一律拒绝
            return ValidationResult.fail("SQL中禁止使用块注释");
        }
        if (stripped.toUpperCase(Locale.ROOT).startsWith("SELECT")) {
            Matcher m = SELECT_FORBIDDEN_PATTERN.matcher(stripped);
            if (m.find()) {
                return ValidationResult.fail("禁止使用 " + m.group(1) + " 操作");
            }
        } else {
            Matcher m = WRITE_FORBIDDEN_PATTERN.matcher(stripped);
            if (m.find()) {
                return ValidationResult.fail("禁止使用 " + m.group(1) + " 操作");
            }
        }
        Matcher sensitive = SENSITIVE_COLUMN_PATTERN.matcher(stripped);
        if (sensitive.find()) {
            log.warn("检测到敏感列访问，已拦截: column={}", sensitive.group(1));
            return ValidationResult.fail("禁止访问敏感字段: " + sensitive.group(1));
        }

        // 2. Druid 解析：单条语句、解析失败即拒绝（fail-closed）
        List<SQLStatement> statements;
        try {
            statements = parseStatements(sql);
        } catch (Exception e) {
            log.info("SQL解析失败，已拒绝: {}", e.getMessage());
            return ValidationResult.fail("仅支持单条标准 PostgreSQL 语句");
        }
        if (statements.size() != 1) {
            return ValidationResult.fail("禁止执行多条SQL语句");
        }
        SQLStatement stmt = statements.get(0);

        // 3. 白名单校验：语句涉及的全部表（含子查询）必须在启用策略内
        Set<String> tables = extractTables(stmt);
        if (tables.isEmpty()) {
            return ValidationResult.fail("未识别到目标表");
        }
        if (!policyHolder.isAnyPolicyEnabled()) {
            return ValidationResult.fail("未配置可查询表白名单，请联系管理员配置");
        }
        for (String table : tables) {
            if (policyHolder.getEnabledPolicy(table) == null) {
                return ValidationResult.fail("表 " + table + " 不在可查询白名单内，请仅使用白名单内的业务表");
            }
        }

        // 4. 按语句类型路由双通道
        Long userId = resolveCurrentUserId();
        if (stmt instanceof SQLSelectStatement selectStmt) {
            return validateSelect(selectStmt, userId);
        }
        if (stmt instanceof SQLInsertStatement insertStmt) {
            return validateWrite(insertStmt, userId);
        }
        if (stmt instanceof SQLUpdateStatement updateStmt) {
            return validateWrite(updateStmt, userId, tables);
        }
        if (stmt instanceof SQLDeleteStatement deleteStmt) {
            return validateWrite(deleteStmt, userId, tables);
        }
        return ValidationResult.fail("只允许SELECT/INSERT/UPDATE/DELETE");
    }

    /**
     * SELECT 通道校验
     * 禁 UNION / SELECT *；按查询块注入行级谓词；LIMIT 上限保证
     */
    private ValidationResult validateSelect(SQLSelectStatement selectStmt, Long userId) {
        SQLSelectQuery query = selectStmt.getSelect().getQuery();
        // UNION 会被拆分多源数据，拒绝（保持与旧文本防线一致）
        if (query instanceof SQLUnionQuery) {
            return ValidationResult.fail("禁止使用 UNION 操作");
        }

        List<SQLSelectQueryBlock> blocks = new ArrayList<>();
        collectQueryBlocks(selectStmt, blocks);

        // SELECT * 拒绝（防敏感列经通配符泄露；仅检查各查询块选择项，不影响 COUNT(*)）
        for (SQLSelectQueryBlock block : blocks) {
            for (SQLSelectItem item : block.getSelectList()) {
                if (isAllColumn(item.getExpr())) {
                    return ValidationResult.fail("禁止使用 SELECT *，请明确列名");
                }
            }
        }

        // 行级谓词注入：每个查询块按其 FROM 表独立注入，子查询同样覆盖
        for (SQLSelectQueryBlock block : blocks) {
            Set<String> fromTables = collectFromTables(block.getFrom());
            for (String table : fromTables) {
                SqlTablePolicy policy = policyHolder.getEnabledPolicy(table);
                SQLExpr predicate;
                try {
                    predicate = buildRowPredicate(policy, userId);
                } catch (RowIsolationException e) {
                    return ValidationResult.fail("表 " + table + " 按用户隔离，但当前无法识别用户身份，已拒绝查询");
                }
                if (predicate == null) {
                    continue;
                }
                andWhere(block, predicate);
            }
            ensureLimit(block);
        }

        return success(selectStmt, STATEMENT_TYPE_SELECT);
    }

    /**
     * INSERT 通道校验：白名单已过，剩余拦截 INSERT...SELECT 数据搬运
     */
    private ValidationResult validateWrite(SQLInsertStatement insertStmt, Long userId) {
        if (insertStmt.getQuery() != null) {
            return ValidationResult.fail("INSERT 不允许携带 SELECT 子句");
        }
        return success(insertStmt, STATEMENT_TYPE_WRITE);
    }

    /**
     * UPDATE/DELETE 通道校验
     * 强制 WHERE（行级谓词或用户自带条件），保留白名单联动与行级谓词注入；PG 方言不追加 LIMIT
     */
    private ValidationResult validateWrite(SQLStatement stmt, Long userId, Set<String> tables) {
        if (tables.size() > 1) {
            return ValidationResult.fail("写操作仅支持单表");
        }
        String table = tables.iterator().next();
        SqlTablePolicy policy = policyHolder.getEnabledPolicy(table);
        SQLExpr predicate;
        try {
            predicate = buildRowPredicate(policy, userId);
        } catch (RowIsolationException e) {
            return ValidationResult.fail("表 " + table + " 按用户隔离，但当前无法识别用户身份，已拒绝操作");
        }

        SQLExpr where = invokeGetWhere(stmt);
        if (where == null && predicate == null) {
            return ValidationResult.fail("修改操作必须带 WHERE 条件");
        }
        SQLExpr combined = predicate == null ? where : andCombine(where, predicate);
        invokeSetWhere(stmt, combined);
        return success(stmt, STATEMENT_TYPE_WRITE);
    }

    // ==================== 行级谓词 ====================

    /**
     * 按策略生成行级谓词
     * user_scoped → user_column = uid；visibility_aware → (visibility_col = 1 OR user_column = uid)；
     * 两者叠加时取 AND 由调用方合并；无需隔离返回 null
     *
     * @return 谓词表达式；需要隔离但当前用户不可知时返回特殊失败（由 fail 结果表达）
     */
    private SQLExpr buildRowPredicate(SqlTablePolicy policy, Long userId) {
        if (policy == null) {
            return null;
        }
        String column = policy.getUserColumn() == null ? "user_id" : policy.getUserColumn();
        SQLExpr userCol = new SQLIdentifierExpr(column);
        boolean visibilityAware = isOn(policy.getVisibilityAware());
        boolean userScoped = isOn(policy.getUserScoped());

        if (visibilityAware) {
            // 公有记录（visibility = 1）豁免隔离；私有记录要求归属当前用户
            SQLExpr visCol = new SQLIdentifierExpr("visibility");
            SQLExpr publicOnly = new SQLBinaryOpExpr(visCol, SQLBinaryOperator.Equality, new SQLIntegerExpr(1));
            if (userId == null) {
                return publicOnly;
            }
            SQLExpr ownedByMe = new SQLBinaryOpExpr(userCol, SQLBinaryOperator.Equality, new SQLIntegerExpr(userId));
            // OR 谓词在与其他条件 AND 合并时由 Druid 输出端按运算符优先级自动加括号
            return new SQLBinaryOpExpr(publicOnly, SQLBinaryOperator.BooleanOr, ownedByMe);
        }
        if (userScoped) {
            if (userId == null) {
                // 需要用户隔离但拿不到当前用户：由调用方以失败结果拦截
                throw new RowIsolationException(column);
            }
            return new SQLBinaryOpExpr(userCol, SQLBinaryOperator.Equality, new SQLIntegerExpr(userId));
        }
        return null;
    }

    /** 行级隔离所需用户上下文缺失异常（内部流转用，转为校验失败） */
    private static class RowIsolationException extends RuntimeException {
        RowIsolationException(String column) {
            super(column);
        }
    }

    /**
     * 向查询块叠加 WHERE 条件（原条件加括号后 AND 新谓词，保证 OR 语义不被改变）
     */
    private void andWhere(SQLSelectQueryBlock block, SQLExpr predicate) {
        SQLExpr old = block.getWhere();
        block.setWhere(andCombine(old, predicate));
    }

    /**
     * 布尔 AND 合并：原表达式为空直接返回新表达式，否则 原表达式 AND 新表达式
     * （Druid 输出端按运算符优先级为低优先级子表达式补括号，OR 语义不受影响）
     */
    private SQLExpr andCombine(SQLExpr oldExpr, SQLExpr predicate) {
        if (oldExpr == null) {
            return predicate;
        }
        return new SQLBinaryOpExpr(oldExpr, SQLBinaryOperator.BooleanAnd, predicate);
    }

    /**
     * 解析当前用户ID：聊天链路（UserContextHolder）优先，HTTP 请求链路（UserContext）兜底
     */
    private Long resolveCurrentUserId() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            userId = UserContext.getUserId();
        }
        return userId;
    }

    // ==================== AST 工具 ====================

    /**
     * 解析 SQL 为语句列表（PostgreSQL 方言）
     */
    private List<SQLStatement> parseStatements(String sql) {
        PGSQLStatementParser parser = new PGSQLStatementParser(sql);
        return parser.parseStatementList();
    }

    /**
     * 提取语句涉及的全部真实表名（含子查询/JOIN，小写归一）
     */
    private Set<String> extractTables(SQLStatement stmt) {
        PGSchemaStatVisitor visitor = new PGSchemaStatVisitor();
        stmt.accept(visitor);
        Set<String> tables = new LinkedHashSet<>();
        for (Map.Entry<TableStat.Name, TableStat> entry : visitor.getTables().entrySet()) {
            tables.add(entry.getKey().getName().toLowerCase(Locale.ROOT));
        }
        return tables;
    }

    /**
     * 深度收集语句内全部查询块（含 WHERE/SELECT 项中的子查询）
     */
    private void collectQueryBlocks(SQLStatement stmt, List<SQLSelectQueryBlock> out) {
        stmt.accept(new com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter() {
            @Override
            public void endVisit(SQLSelectQueryBlock x) {
                out.add(x);
            }
        });
    }

    /**
     * 收集 FROM 数据源的真实表名（展开 JOIN 与派生表外层别名）
     */
    private Set<String> collectFromTables(SQLTableSource from) {
        Set<String> tables = new LinkedHashSet<>();
        if (from instanceof SQLExprTableSource exprSource) {
            String name = simpleTableName(exprSource.getExpr());
            if (name != null) {
                tables.add(name);
            }
        } else if (from instanceof SQLJoinTableSource join) {
            tables.addAll(collectFromTables(join.getLeft()));
            tables.addAll(collectFromTables(join.getRight()));
        }
        // SQLSubqueryTableSource（派生表）：真实表在内层查询块处理，此处不收集别名
        return tables;
    }

    /**
     * 从表表达式取简单表名（兼容 db.table 与反引号）
     */
    private String simpleTableName(SQLExpr expr) {
        if (expr instanceof SQLIdentifierExpr id) {
            return id.getName().toLowerCase(Locale.ROOT);
        }
        if (expr instanceof SQLPropertyExpr prop) {
            return prop.getName().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /**
     * 判断选择项是否为通配列（* 或 t.*）
     */
    private boolean isAllColumn(SQLExpr expr) {
        if (expr instanceof SQLAllColumnExpr) {
            return true;
        }
        return expr instanceof SQLPropertyExpr prop && "*".equals(prop.getName());
    }

    /**
     * 保证查询块 LIMIT 行数不超过上限（无 LIMIT 追加，超限改写，参数化 LIMIT 一律收口）
     */
    private void ensureLimit(SQLSelectQueryBlock block) {
        SQLLimit limit = block.getLimit();
        if (limit == null) {
            block.setLimit(new SQLLimit(new SQLIntegerExpr(DEFAULT_MAX_ROWS)));
            return;
        }
        SQLExpr rowCount = limit.getRowCount();
        if (rowCount instanceof SQLIntegerExpr intExpr) {
            if (intExpr.getNumber().intValue() > DEFAULT_MAX_ROWS) {
                limit.setRowCount(new SQLIntegerExpr(DEFAULT_MAX_ROWS));
            }
            return;
        }
        // 非字面量行数（表达式/占位）一律收口为上限，fail-closed
        limit.setRowCount(new SQLIntegerExpr(DEFAULT_MAX_ROWS));
    }

    // PG 方言 UPDATE/DELETE 原生不支持 LIMIT，已移除 ensureWriteLimit；WHERE 强制与行级谓词保留

    /**
     * 反射读取 UPDATE/DELETE 的 WHERE（兼容 getWhere 的版本差异）
     */
    private SQLExpr invokeGetWhere(SQLStatement stmt) {
        try {
            return (SQLExpr) stmt.getClass().getMethod("getWhere").invoke(stmt);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * 反射写入 UPDATE/DELETE 的 WHERE
     */
    private void invokeSetWhere(SQLStatement stmt, SQLExpr where) {
        try {
            stmt.getClass().getMethod("setWhere", SQLExpr.class).invoke(stmt, where);
        } catch (ReflectiveOperationException e) {
            log.warn("UPDATE/DELETE WHERE 回写失败（Druid API 不兼容）: {}", e.getMessage());
        }
    }

    /**
     * 构建成功结果：AST 还原为规范化 SQL
     */
    private ValidationResult success(SQLStatement stmt, String statementType) {
        String sanitized = SQLUtils.toSQLString(stmt, DbType.postgresql).trim();
        if (sanitized.endsWith(";")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1).trim();
        }
        ValidationResult result = ValidationResult.success(sanitized);
        result.setStatementType(statementType);
        return result;
    }

    /**
     * 剥离单/双引号字符串字面量（用于文本兜底检查，避免字面量内容触发误杀）
     *
     * @param sql 原始 SQL
     * @return 字面量置空后的 SQL
     */
    private static String stripStringLiterals(String sql) {
        return sql.replaceAll("'[^']*'", "''").replaceAll("\"[^\"]*\"", "\"\"");
    }

    /**
     * 是否开启（1=开）
     */
    private static boolean isOn(Integer flag) {
        return flag != null && flag == 1;
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
        /** 校验通过后的 SQL（已做白名单/行级隔离/LIMIT 等安全修正） */
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
