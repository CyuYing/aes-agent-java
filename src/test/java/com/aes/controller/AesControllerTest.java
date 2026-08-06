package com.aes.controller;

import com.aes.service.DatabaseHomeworkService;
import com.aes.service.DatabaseKnowledgeService;
import com.aes.service.AnswerKeyService;
import com.aes.service.AssignmentDocumentService;
import com.aes.service.BatchGradingService;
import com.aes.service.DocumentParserService;
import com.aes.service.GradingRecordService;
import com.aes.service.HomeworkService;
import com.aes.service.KnowledgeService;
import com.aes.model.Dto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

class AesControllerTest {

    private MockMvc mockMvc;
    private HomeworkService homeworkService;
    private DatabaseHomeworkService databaseHomeworkService;
    private GradingRecordService gradingRecordService;
    private AssignmentDocumentService assignmentDocumentService;

    @BeforeEach
    void setUp() {
        homeworkService = mock(HomeworkService.class);
        databaseHomeworkService = mock(DatabaseHomeworkService.class);
        gradingRecordService = mock(GradingRecordService.class);
        assignmentDocumentService = mock(AssignmentDocumentService.class);
        AesController controller = new AesController(
                mock(KnowledgeService.class),
                mock(DocumentParserService.class),
                homeworkService,
                mock(DatabaseKnowledgeService.class),
                databaseHomeworkService,
                gradingRecordService,
                mock(AnswerKeyService.class),
                mock(BatchGradingService.class),
                assignmentDocumentService,
                new ObjectMapper().findAndRegisterModules());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void rejectsWrongDatabaseUploadTypeBeforeCallingGrader() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "answer.txt", "text/plain", "SELECT 1".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/database/homework/grade").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅支持 .docx 格式"));

        verifyNoInteractions(databaseHomeworkService);
    }

    @Test
    void rejectsEmptyHomeworkUploadWithReadableError() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[0]);

        mockMvc.perform(multipart("/api/homework/parse").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请上传作业文档"));
    }

    @Test
    void returnsDetectedCourseTypeWithSingleHomeworkPreview() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "数据库作业.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[]{1});
        Dto.QuestionEntry question = new Dto.QuestionEntry(
                1, "SQL 查询", "使用 SELECT 完成查询",
                "SELECT 1", "sql", "programming", "SELECT 1", List.of());
        Dto.HomeworkPreview preview = new Dto.HomeworkPreview(
                "数据库作业.docx", "java", List.of(question),
                new Dto.QuestionRecognitionInfo(
                        true, true, "ai-confirmed", "AI 已复核题目边界",
                        1, 1, 0.95));
        when(homeworkService.previewHomework(any(), eq(true))).thenReturn(preview);
        when(assignmentDocumentService.detectCourseType(any(), eq(preview.questions())))
                .thenReturn("database");

        mockMvc.perform(multipart("/api/homework/parse").file(file)
                        .param("aiQuestionRecognition", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("数据库作业.docx"))
                .andExpect(jsonPath("$.courseType").value("database"))
                .andExpect(jsonPath("$.questions.length()").value(1))
                .andExpect(jsonPath("$.recognition.aiUsed").value(true));
    }

    @Test
    void rejectsMalformedPerQuestionConfiguration() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "answer.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/homework/grade")
                        .file(file)
                        .param("configs", "{not-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("逐题批改配置格式不正确"));

        verifyNoInteractions(homeworkService);
    }

    @Test
    void reviewsAndExportsGradingRecords() throws Exception {
        Instant now = Instant.parse("2026-07-20T01:00:00Z");
        GradingRecordService.RecordSummary summary = new GradingRecordService.RecordSummary(
                "20260720010000-abcd1234", now, "java", "作业.docx",
                90, 100, "APPROVED", "通过", "teacher", now, "teacher",
                "张三", "J001", "计科一班", "作业1", 4);
        when(gradingRecordService.review(eq(summary.id()), eq("APPROVED"), eq("通过"), eq("teacher")))
                .thenReturn(summary);
        when(gradingRecordService.exportCsv()).thenReturn("\uFEFF记录ID\r\n".getBytes(StandardCharsets.UTF_8));
        Principal principal = () -> "teacher";

        mockMvc.perform(patch("/api/grading/records/{id}/review", summary.id())
                        .principal(principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\",\"note\":\"通过\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"));

        mockMvc.perform(get("/api/grading/records/export.csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=grading-records.csv"))
                .andExpect(content().bytes("\uFEFF记录ID\r\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void searchesAStudentsSpecificQuestionWithCombinedParameters() throws Exception {
        Instant now = Instant.parse("2026-07-20T01:00:00Z");
        GradingRecordService.QuestionHit hit = new GradingRecordService.QuestionHit(
                "20260720010000-abcd1234", now, "java", "张三作业.docx",
                88, 100, "PENDING_REVIEW", "张三", "J001", "计科一班",
                3, "多态", 20, 25, "subjective", "answer-key-ai",
                "学生答案", "批改评语");
        when(gradingRecordService.searchQuestions(any())).thenReturn(
                new GradingRecordService.SearchPage<>(List.of(hit), 1, 30, 0));

        mockMvc.perform(get("/api/grading/questions/search")
                        .param("studentName", "张三")
                        .param("questionIndex", "3")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].studentId").value("J001"))
                .andExpect(jsonPath("$.items[0].questionIndex").value(3));

        verify(gradingRecordService).searchQuestions(argThat(criteria ->
                "张三".equals(criteria.studentName())
                        && Integer.valueOf(3).equals(criteria.questionIndex())
                        && criteria.from() != null && criteria.to() != null));
    }

    @Test
    void streamOnlySignalsDoneAfterTheResultWasSaved() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "answer.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[]{1, 2, 3});
        Dto.HomeworkResult result = new Dto.HomeworkResult("answer.docx", 80, 100, List.of());
        Dto.StudentIdentity identity = new Dto.StudentIdentity("张三", "J001", "一班", "作业1");
        when(homeworkService.gradeHomeworkStream(
                any(), any(), any(), any(), any(), eq(false), any())).thenReturn(result);
        when(assignmentDocumentService.parseIdentity(any())).thenReturn(identity);

        mockMvc.perform(multipart("/api/homework/grade/stream")
                        .file(file)
                        .param("configs", "[]"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event: done")));

        verify(gradingRecordService).saveJava(result, "local-teacher", identity);
    }
}
