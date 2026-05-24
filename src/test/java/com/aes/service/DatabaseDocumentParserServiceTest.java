package com.aes.service;

import com.aes.model.Dto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseDocumentParserServiceTest {

    private final DatabaseDocumentParserService service = new DatabaseDocumentParserService();

    @Test
    void parseTextExtractsMultipleDatabaseQuestions() {
        String text = """
                数据库作业
                姓名
                张三

                第1题 查询用户
                查询年龄大于 18 岁的用户姓名。
                初始化 SQL
                ```sql
                CREATE TABLE users(id INT, name VARCHAR(20), age INT);
                INSERT INTO users VALUES (1, 'Alice', 20);
                ```
                学生 SQL
                ```sql
                SELECT name FROM users WHERE age > 18;
                ```

                第2题 新增课程
                向课程表插入一条课程记录。
                学生 SQL
                ```sql
                INSERT INTO courses(id, name) VALUES (1, 'Database');
                ```
                """;

        List<Dto.DatabaseQuestionEntry> questions = service.parseText(text);

        assertThat(questions).hasSize(2);
        assertThat(questions.get(0).title()).contains("第1题");
        assertThat(questions.get(0).description()).contains("查询年龄大于 18 岁");
        assertThat(questions.get(0).setupSql()).contains("CREATE TABLE users");
        assertThat(questions.get(0).answerSql()).contains("SELECT name FROM users");
        assertThat(questions.get(1).answerSql()).contains("INSERT INTO courses");
    }

    @Test
    void parseTextFallsBackToSqlKeywordWhenNoCodeBlockExists() {
        String text = """
                第1题
                查询所有学生姓名。
                SELECT name FROM student;
                """;

        List<Dto.DatabaseQuestionEntry> questions = service.parseText(text);

        assertThat(questions).hasSize(1);
        assertThat(questions.get(0).description()).contains("查询所有学生姓名");
        assertThat(questions.get(0).answerSql()).contains("SELECT name FROM student");
    }
}
