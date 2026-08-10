package com.aes.service;

import com.aes.model.Dto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseExecutionServiceTest {

    private final DatabaseExecutionService service = new DatabaseExecutionService();

    @BeforeEach
    void configureSandbox() {
        String url = System.getenv("AES_TEST_MYSQL_SANDBOX_URL");
        if (url != null && !url.isBlank()) {
            ReflectionTestUtils.setField(service, "sandboxUrl", url);
            ReflectionTestUtils.setField(service, "sandboxUsername",
                    System.getenv().getOrDefault("AES_TEST_MYSQL_SANDBOX_USERNAME", "aes_sandbox"));
            ReflectionTestUtils.setField(service, "sandboxPassword",
                    System.getenv("AES_TEST_MYSQL_SANDBOX_PASSWORD"));
        }
    }

    @Test
    void splitSqlStatementsKeepsSemicolonsInsideStringsAndComments() {
        String sql = """
                SELECT 'a;b' AS value;
                -- comment with ; should stay
                SELECT 2 AS n;
                /* block ; comment */
                SELECT `a;b` FROM users;
                """;

        List<String> statements = service.splitSqlStatements(sql);

        assertThat(statements).hasSize(3);
        assertThat(statements.get(0)).contains("'a;b'");
        assertThat(statements.get(1)).contains("comment with ;").contains("SELECT 2");
        assertThat(statements.get(2)).contains("block ; comment").contains("`a;b`");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AES_TEST_MYSQL_SANDBOX_URL", matches = ".+")
    void executeRunsDdlDmlAndSelect() {
        String setupSql = """
                CREATE TABLE users(id INT PRIMARY KEY, name VARCHAR(20));
                INSERT INTO users(id, name) VALUES (1, 'Alice'), (2, 'Bob');
                """;
        String answerSql = "SELECT name FROM users WHERE id = 1;";

        Dto.SqlExecutionResult result = service.execute(setupSql, answerSql);

        assertThat(result.success()).isTrue();
        assertThat(result.statements()).hasSize(3);
        Dto.SqlStatementResult select = result.statements().get(2);
        assertThat(select.success()).isTrue();
        assertThat(select.columns()).containsExactly("name");
        assertThat(select.rows()).containsExactlyInAnyOrderElementsOf(List.of(
                java.util.Map.of("name", "Alice")
        ));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AES_TEST_MYSQL_SANDBOX_URL", matches = ".+")
    void executeCapturesSyntaxErrors() {
        Dto.SqlExecutionResult result = service.execute("", "SELECT FROM users;");

        assertThat(result.success()).isFalse();
        assertThat(result.errorSummary()).isNotBlank();
        assertThat(result.statements()).hasSize(1);
        assertThat(result.statements().get(0).success()).isFalse();
        assertThat(result.statements().get(0).error()).isNotBlank();
    }

    @Test
    void executeBlocksForbiddenStatementsBeforeRunningThem() {
        Dto.SqlExecutionResult result = service.execute("", "SELECT LOAD_FILE('secret.txt');");

        assertThat(result.success()).isFalse();
        assertThat(result.statements()).hasSize(1);
        assertThat(result.statements().get(0).error()).contains("禁止执行");
    }
}
