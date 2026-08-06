package com.aes.service;

import com.aes.model.Dto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "AES_TEST_MYSQL_URL", matches = ".+")
class GradingRecordServiceTest {

    @TempDir
    Path tempDir;

    private ObjectMapper objectMapper;
    private GradingRecordService service;
    private Path legacyDirectory;
    private String databaseUrl;
    private String databaseUsername;
    private String databasePassword;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        legacyDirectory = tempDir.resolve("legacy-records");
        databaseUrl = System.getenv("AES_TEST_MYSQL_URL");
        databaseUsername = System.getenv().getOrDefault("AES_TEST_MYSQL_USERNAME", "aes_agent");
        databasePassword = System.getenv("AES_TEST_MYSQL_PASSWORD");
        service = newService(legacyDirectory);
        clearDatabase();
    }

    @Test
    void persistsIdentityAndQuestionDetailsInDatabaseAndSupportsCombinedSearch() throws Exception {
        Dto.QuestionResult question = new Dto.QuestionResult(
                1, "继承与多态", 18, 20, Map.of(), "覆盖关系判断正确",
                List.of(), "subjective", "answer-key-ai",
                "子类重写父类方法", "子类可重写非 final 方法", List.of(), "");
        Dto.HomeworkResult result = new Dto.HomeworkResult(
                "张三-Java作业.docx", 86, 100, List.of(question));
        Dto.StudentIdentity identity = new Dto.StudentIdentity(
                "张三", "J20260001", "计科一班", "作业1");

        GradingRecordService.RecordSummary saved =
                service.saveJava(result, "teacher-a", identity);

        assertThat(saved.reviewStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(saved.studentName()).isEqualTo("张三");
        assertThat(saved.questionCount()).isEqualTo(1);
        assertThat(service.list(20)).extracting(GradingRecordService.RecordSummary::id)
                .containsExactly(saved.id());
        assertThat(service.get(saved.id()).fileName()).isEqualTo("张三-Java作业.docx");
        assertThat(service.storageStats().engine()).isEqualTo("MySQL");
        assertThat(service.storageStats().fileName()).isEqualTo("aes_agent");
        try (var files = Files.list(legacyDirectory)) {
            assertThat(files.filter(path -> path.toString().endsWith(".json"))).isEmpty();
        }

        GradingRecordService.SearchPage<GradingRecordService.RecordSummary> records =
                service.search(criteria("", "张三", "J20260001", "计科一班", 1, "多态"));
        assertThat(records.total()).isEqualTo(1);
        assertThat(records.items()).extracting(GradingRecordService.RecordSummary::id)
                .containsExactly(saved.id());

        GradingRecordService.SearchPage<GradingRecordService.QuestionHit> questions =
                service.searchQuestions(criteria("覆盖", "张三", "", "", 1, "多态"));
        assertThat(questions.total()).isEqualTo(1);
        assertThat(questions.items().get(0).studentId()).isEqualTo("J20260001");
        assertThat(questions.items().get(0).score()).isEqualTo(18);

        GradingRecordService.QuestionDetail detail = service.getQuestion(saved.id(), 1);
        assertThat(detail.studentName()).isEqualTo("张三");
        assertThat(detail.studentAnswer()).contains("重写");
        assertThat(detail.correctAnswer()).contains("final");

        service.saveJava(new Dto.HomeworkResult("李四.docx", 60, 100, List.of(
                        new Dto.QuestionResult(1, "数据库事务", 60, 100,
                                Map.of(), "事务说明", List.of()))),
                "teacher-a", new Dto.StudentIdentity("李四", "J002", "计科一班", "作业1"));
        assertThat(service.search(criteria("", "张三", "", "", null, "数据库")).total())
                .as("题目关键词必须只匹配同一条学生记录中的题目")
                .isZero();

        GradingRecordService.RecordSummary reviewed = service.review(
                saved.id(), "approved", "教师抽查通过", "teacher-b");
        assertThat(reviewed.reviewStatus()).isEqualTo("APPROVED");
        assertThat(reviewed.reviewNote()).isEqualTo("教师抽查通过");
        assertThat(reviewed.reviewedBy()).isEqualTo("teacher-b");
        assertThat(reviewed.reviewedAt()).isNotNull();
    }

    @Test
    void migratesLegacyJsonIdempotentlyAndKeepsOriginalBackup() throws Exception {
        Path otherLegacy = tempDir.resolve("old-json");
        Files.createDirectories(otherLegacy);
        String id = "20260720010000-abcd1234";
        Dto.HomeworkResult result = new Dto.HomeworkResult(
                "旧记录.docx", 70, 100, List.of(new Dto.QuestionResult(
                1, "第一题", 70, 100, Map.of(), "历史报告", List.of())));
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("id", id);
        legacy.put("createdAt", Instant.parse("2026-07-20T01:00:00Z"));
        legacy.put("domain", "java");
        legacy.put("fileName", "旧记录.docx");
        legacy.put("totalScore", 70);
        legacy.put("maxTotalScore", 100);
        legacy.put("reviewStatus", "PENDING_REVIEW");
        legacy.put("reviewNote", "");
        legacy.put("reviewedBy", "");
        legacy.put("reviewedAt", null);
        legacy.put("createdBy", "teacher");
        legacy.put("result", result);
        Path backup = otherLegacy.resolve(id + ".json");
        objectMapper.writeValue(backup.toFile(), legacy);

        GradingRecordService migrated = newService(otherLegacy);

        assertThat(migrated.list(10)).extracting(GradingRecordService.RecordSummary::id)
                .containsExactly(id);
        assertThat(migrated.getQuestion(id, 1).report()).isEqualTo("历史报告");
        assertThat(Files.isRegularFile(backup)).isTrue();
        assertThat(migrated.storageStats().recordCount()).isEqualTo(1);
        assertThat(migrated.storageStats().questionCount()).isEqualTo(1);
    }

    @Test
    void exportsUtf8CsvAndEscapesFileNames() {
        Dto.DatabaseHomeworkResult result = new Dto.DatabaseHomeworkResult(
                "=危险公式,数据库作业.docx", 91, 100, List.of());
        service.saveDatabase(result, "local-teacher",
                new Dto.StudentIdentity("李四", "D002", "数据一班", "作业2"));

        String csv = new String(service.exportCsv(), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("\uFEFF记录ID,批改时间");
        assertThat(csv).contains("\"'=危险公式,数据库作业.docx\"");
        assertThat(csv).contains("\"database\"");
        assertThat(csv).contains("\"李四\",\"D002\",\"数据一班\"");
    }

    @Test
    void rejectsUnknownStatusAndUnsafeIds() {
        Dto.HomeworkResult result = new Dto.HomeworkResult("作业.docx", 60, 100, List.of());
        GradingRecordService.RecordSummary saved = service.saveJava(result, null);

        assertThatThrownBy(() -> service.review(saved.id(), "DELETED", "", "teacher"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("审核状态");
        assertThatThrownBy(() -> service.review(saved.id(), "NEEDS_REVISION", "", "teacher"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须填写");
        assertThatThrownBy(() -> service.get("../outside"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ID 格式");
    }

    private GradingRecordService newService(Path legacy) {
        GradingRecordService result = new GradingRecordService(objectMapper);
        ReflectionTestUtils.setField(result, "recordsPath", legacy.toString());
        ReflectionTestUtils.setField(result, "configuredJdbcUrl", databaseUrl);
        ReflectionTestUtils.setField(result, "configuredUsername", databaseUsername);
        ReflectionTestUtils.setField(result, "configuredPassword", databasePassword);
        result.init();
        return result;
    }

    private void clearDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                databaseUrl, databaseUsername, databasePassword);
             Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS=0");
            statement.execute("TRUNCATE TABLE grading_question");
            statement.execute("TRUNCATE TABLE grading_record");
            statement.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }

    private GradingRecordService.SearchCriteria criteria(
            String keyword, String studentName, String studentId, String className,
            Integer questionIndex, String questionKeyword) {
        return new GradingRecordService.SearchCriteria(
                keyword, "", studentName, studentId, className, "",
                null, null, null, null, questionIndex, questionKeyword,
                30, 0, "createdAt", "desc");
    }
}
