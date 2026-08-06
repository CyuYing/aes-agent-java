package com.aes.service;

import com.aes.model.Dto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PortableDemoSamplesTest {

    @Test
    void javaDemoCoversAllSupportedQuestionTypes() throws Exception {
        Path sample = Path.of("samples", "Java完整功能演示作业.docx");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                sample.getFileName().toString(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Files.readAllBytes(sample));

        List<Dto.QuestionEntry> questions = new DocumentParserService().parseDocx(file);

        assertThat(questions).hasSize(4);
        assertThat(questions).extracting(Dto.QuestionEntry::questionType)
                .containsExactly("choice", "programming", "subjective", "image");
        assertThat(questions.get(0).studentAnswer()).isEqualTo("B");
        assertThat(questions.get(1).code()).contains("class Book", "DemoBookApp");
        assertThat(questions.get(3).images()).hasSize(1);
        assertThat(questions.get(3).images().get(0).role()).isEqualTo("student");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AES_TEST_MYSQL_SANDBOX_URL", matches = ".+")
    void databaseDemoCoversSuccessfulQueriesAndSafetyBlocking() throws Exception {
        Path sample = Path.of("samples", "数据库完整功能演示作业.docx");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                sample.getFileName().toString(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Files.readAllBytes(sample));

        List<Dto.DatabaseQuestionEntry> questions =
                new DatabaseDocumentParserService().parseDocx(file);
        assertThat(questions).hasSize(3);
        assertThat(questions).allSatisfy(question -> {
            assertThat(question.setupSql()).isNotBlank();
            assertThat(question.answerSql()).isNotBlank();
        });

        DatabaseExecutionService executionService = new DatabaseExecutionService();
        ReflectionTestUtils.setField(executionService, "sandboxUrl",
                System.getenv("AES_TEST_MYSQL_SANDBOX_URL"));
        ReflectionTestUtils.setField(executionService, "sandboxUsername",
                System.getenv().getOrDefault("AES_TEST_MYSQL_SANDBOX_USERNAME", "aes_sandbox"));
        ReflectionTestUtils.setField(executionService, "sandboxPassword",
                System.getenv("AES_TEST_MYSQL_SANDBOX_PASSWORD"));
        Dto.SqlExecutionResult joinResult = executionService.execute(
                questions.get(0).setupSql(), questions.get(0).answerSql());
        Dto.SqlExecutionResult aggregateResult = executionService.execute(
                questions.get(1).setupSql(), questions.get(1).answerSql());
        Dto.SqlExecutionResult blockedResult = executionService.execute(
                questions.get(2).setupSql(), questions.get(2).answerSql());

        assertThat(joinResult.success()).isTrue();
        assertThat(joinResult.statements().get(joinResult.statements().size() - 1).rows()).hasSize(2);
        assertThat(aggregateResult.success()).isTrue();
        assertThat(aggregateResult.statements().get(aggregateResult.statements().size() - 1).rows()).hasSize(1);
        assertThat(blockedResult.success()).isFalse();
        assertThat(blockedResult.errorSummary()).contains("禁止执行的高风险语句");
    }
}
