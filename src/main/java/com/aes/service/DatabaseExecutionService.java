package com.aes.service;

import com.aes.model.Dto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class DatabaseExecutionService {

    private static final int MAX_STATEMENTS = 40;
    private static final int MAX_RESULT_ROWS = 50;
    private static final int QUERY_TIMEOUT_SECONDS = 5;

    private static final Pattern FORBIDDEN_SQL = Pattern.compile(
            "(?is)(?:\\b(?:LOAD\\s+DATA|LOAD_FILE|FILE_READ|INTO\\s+(?:OUTFILE|DUMPFILE)|" +
                    "CREATE\\s+(?:DATABASE|SCHEMA|USER|ROLE|PROCEDURE|FUNCTION|TRIGGER|EVENT|SERVER)|" +
                    "DROP\\s+(?:DATABASE|SCHEMA|USER|ROLE|SERVER)|ALTER\\s+USER|GRANT|REVOKE|" +
                    "SET\\s+(?:GLOBAL|PERSIST|PASSWORD)|RESET\\s+MASTER|PURGE\\s+BINARY|" +
                    "KILL|SHUTDOWN|RESTART|INSTALL\\s+PLUGIN|UNINSTALL\\s+PLUGIN|CLONE|" +
                    "LOCK\\s+INSTANCE|UNLOCK\\s+INSTANCE|PREPARE|EXECUTE|DEALLOCATE\\s+PREPARE|" +
                    "HANDLER|USE)\\b|(?:`?)(?:mysql|information_schema|performance_schema|sys)(?:`?)\\s*\\.)"
    );

    @Value("${aes.sql-sandbox.url:${AES_SQL_SANDBOX_URL:jdbc:mysql://127.0.0.1:3307/aes_sql_sandbox?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true}}")
    private String sandboxUrl;

    @Value("${aes.sql-sandbox.username:${AES_SQL_SANDBOX_USERNAME:aes_sandbox}}")
    private String sandboxUsername;

    @Value("${aes.sql-sandbox.password:${AES_SQL_SANDBOX_PASSWORD:}}")
    private String sandboxPassword;

    public synchronized Dto.SqlExecutionResult execute(String setupSql, String answerSql) {
        List<String> setupStatements = splitSqlStatements(setupSql);
        List<String> answerStatements = splitSqlStatements(answerSql);
        List<String> allStatements = new ArrayList<>();
        allStatements.addAll(setupStatements);
        allStatements.addAll(answerStatements);

        List<Dto.SqlStatementResult> results = new ArrayList<>();
        if (allStatements.size() > MAX_STATEMENTS) {
            String reason = "SQL 语句数量超过限制: " + allStatements.size() + " > " + MAX_STATEMENTS;
            results.add(failedStatement("", reason));
            return new Dto.SqlExecutionResult(false, results, reason);
        }

        for (String sql : allStatements) {
            if (FORBIDDEN_SQL.matcher(sql).find()) {
                String reason = "SQL 包含禁止执行的高风险语句";
                results.add(failedStatement(sql, reason));
                return new Dto.SqlExecutionResult(false, results, reason);
            }
        }

        boolean success = true;
        StringBuilder errors = new StringBuilder();

        try {
            validateConfiguration();
            try (Connection conn = DriverManager.getConnection(
                    sandboxUrl, sandboxUsername, sandboxPassword)) {
                validateSandboxConnection(conn);
                clearSandbox(conn);
                try (Statement statement = conn.createStatement()) {
                    statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                    statement.execute("SET SESSION MAX_EXECUTION_TIME="
                            + (QUERY_TIMEOUT_SECONDS * 1000));

                    for (String sql : allStatements) {
                        Dto.SqlStatementResult result = executeOne(statement, sql);
                        results.add(result);
                        if (!result.success()) {
                            success = false;
                            if (!errors.isEmpty()) errors.append("; ");
                            errors.append(result.error());
                        }
                    }
                } finally {
                    try {
                        clearSandbox(conn);
                    } catch (SQLException cleanupError) {
                        success = false;
                        if (!errors.isEmpty()) errors.append("; ");
                        errors.append("清理 MySQL 沙箱失败: ").append(cleanupError.getMessage());
                    }
                }
            }
        } catch (SQLException | IllegalStateException e) {
            success = false;
            String reason = e.getMessage();
            if (!errors.isEmpty()) errors.append("; ");
            errors.append(reason);
            results.add(failedStatement("", reason));
        }

        return new Dto.SqlExecutionResult(success, results, errors.toString());
    }

    private void validateConfiguration() {
        if (sandboxUrl == null || !sandboxUrl.toLowerCase().startsWith("jdbc:mysql://")) {
            throw new IllegalStateException("SQL 沙箱必须配置 jdbc:mysql:// URL");
        }
        if (sandboxUsername == null || sandboxUsername.isBlank()
                || sandboxPassword == null || sandboxPassword.isBlank()) {
            throw new IllegalStateException("MySQL SQL 沙箱账号或密码未配置");
        }
    }

    private void validateSandboxConnection(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        if (product == null || !product.toLowerCase().contains("mysql")) {
            throw new SQLException("SQL 执行环境不是 MySQL");
        }
        String catalog = connection.getCatalog();
        if (catalog == null || catalog.isBlank()) {
            throw new SQLException("SQL 沙箱 URL 必须指定独立数据库");
        }
        if (!"aes_sql_sandbox".equalsIgnoreCase(catalog)) {
            throw new SQLException("SQL 作业只能在 aes_sql_sandbox 独立数据库中执行");
        }
    }

    private void clearSandbox(Connection connection) throws SQLException {
        List<String> views = new ArrayList<>();
        List<String> tables = new ArrayList<>();
        String catalog = connection.getCatalog();
        try (ResultSet rows = connection.getMetaData().getTables(
                catalog, null, "%", new String[]{"TABLE", "VIEW"})) {
            while (rows.next()) {
                String name = rows.getString("TABLE_NAME");
                String type = rows.getString("TABLE_TYPE");
                if ("VIEW".equalsIgnoreCase(type)) views.add(name);
                else tables.add(name);
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS=0");
            try {
                for (String view : views) {
                    statement.execute("DROP VIEW IF EXISTS " + quoteIdentifier(view));
                }
                for (String table : tables) {
                    statement.execute("DROP TABLE IF EXISTS " + quoteIdentifier(table));
                }
            } finally {
                statement.execute("SET FOREIGN_KEY_CHECKS=1");
            }
        }
    }

    private String quoteIdentifier(String value) {
        return "`" + value.replace("`", "``") + "`";
    }

    public List<String> splitSqlStatements(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }

        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean backtick = false;
        boolean lineComment = false;
        boolean blockComment = false;

        String text = sql.replace("\r\n", "\n").replace('\r', '\n').replace("\uFEFF", "");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';

            if (lineComment) {
                current.append(c);
                if (c == '\n') {
                    lineComment = false;
                }
                continue;
            }

            if (blockComment) {
                current.append(c);
                if (c == '*' && next == '/') {
                    current.append(next);
                    i++;
                    blockComment = false;
                }
                continue;
            }

            if (!singleQuote && !doubleQuote && !backtick) {
                if (c == '-' && next == '-') {
                    current.append(c).append(next);
                    i++;
                    lineComment = true;
                    continue;
                }
                if (c == '#') {
                    current.append(c);
                    lineComment = true;
                    continue;
                }
                if (c == '/' && next == '*') {
                    current.append(c).append(next);
                    i++;
                    blockComment = true;
                    continue;
                }
            }

            if (c == '\'' && !doubleQuote && !backtick) {
                current.append(c);
                if (singleQuote && next == '\'') {
                    current.append(next);
                    i++;
                    continue;
                }
                singleQuote = !singleQuote;
                continue;
            }

            if (c == '"' && !singleQuote && !backtick) {
                current.append(c);
                if (doubleQuote && next == '"') {
                    current.append(next);
                    i++;
                    continue;
                }
                doubleQuote = !doubleQuote;
                continue;
            }

            if (c == '`' && !singleQuote && !doubleQuote) {
                current.append(c);
                backtick = !backtick;
                continue;
            }

            if (c == ';' && !singleQuote && !doubleQuote && !backtick) {
                addStatement(statements, current);
                current.setLength(0);
                continue;
            }

            current.append(c);
        }

        addStatement(statements, current);
        return statements;
    }

    private Dto.SqlStatementResult executeOne(Statement statement, String sql) {
        if (sql == null || sql.isBlank()) {
            return failedStatement(sql, "空 SQL 语句");
        }
        if (FORBIDDEN_SQL.matcher(sql).find()) {
            return failedStatement(sql, "SQL 包含禁止执行的高风险语句");
        }

        try {
            boolean hasResultSet = statement.execute(sql);
            if (hasResultSet) {
                try (ResultSet rs = statement.getResultSet()) {
                    QueryRows rows = readRows(rs);
                    return new Dto.SqlStatementResult(sql, true, -1, rows.columns(), rows.rows(), "");
                }
            }
            return new Dto.SqlStatementResult(sql, true, statement.getUpdateCount(), List.of(), List.of(), "");
        } catch (SQLException e) {
            return failedStatement(sql, e.getMessage());
        }
    }

    private QueryRows readRows(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            columns.add(meta.getColumnLabel(i));
        }

        List<Map<String, String>> rows = new ArrayList<>();
        int rowCount = 0;
        while (rs.next() && rowCount < MAX_RESULT_ROWS) {
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                Object value = rs.getObject(i);
                row.put(columns.get(i - 1), value == null ? "NULL" : String.valueOf(value));
            }
            rows.add(row);
            rowCount++;
        }
        return new QueryRows(columns, rows);
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isBlank()) {
            statements.add(statement);
        }
    }

    private static Dto.SqlStatementResult failedStatement(String sql, String error) {
        return new Dto.SqlStatementResult(
                sql == null ? "" : sql,
                false,
                -1,
                List.of(),
                List.of(),
                error == null ? "SQL 执行失败" : error
        );
    }

    private record QueryRows(List<String> columns, List<Map<String, String>> rows) {}
}
