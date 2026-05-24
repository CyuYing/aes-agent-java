package com.aes.service;

import com.aes.model.Dto;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class DatabaseExecutionService {

    private static final int MAX_STATEMENTS = 40;
    private static final int MAX_RESULT_ROWS = 50;
    private static final int QUERY_TIMEOUT_SECONDS = 5;

    private static final Pattern FORBIDDEN_SQL = Pattern.compile(
            "(?is)\\b(CREATE\\s+ALIAS|RUNSCRIPT|SCRIPT\\s+TO|BACKUP|CSVREAD\\s*\\(|CSVWRITE\\s*\\(|" +
                    "FILE_READ\\s*\\(|FILE_WRITE\\s*\\(|LINKED\\s+TABLE|SHUTDOWN)"
    );

    public Dto.SqlExecutionResult execute(String setupSql, String answerSql) {
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

        String dbName = "aes_db_homework_" + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DATABASE_TO_UPPER=false;NON_KEYWORDS=USER";
        boolean success = true;
        StringBuilder errors = new StringBuilder();

        try (Connection conn = DriverManager.getConnection(url);
             Statement statement = conn.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);

            for (String sql : allStatements) {
                Dto.SqlStatementResult result = executeOne(statement, sql);
                results.add(result);
                if (!result.success()) {
                    success = false;
                    if (!errors.isEmpty()) errors.append("; ");
                    errors.append(result.error());
                }
            }
        } catch (SQLException e) {
            success = false;
            String reason = e.getMessage();
            if (!errors.isEmpty()) errors.append("; ");
            errors.append(reason);
            results.add(failedStatement("", reason));
        }

        return new Dto.SqlExecutionResult(success, results, errors.toString());
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
