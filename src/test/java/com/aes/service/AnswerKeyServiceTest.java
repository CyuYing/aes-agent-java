package com.aes.service;

import com.aes.model.Dto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AnswerKeyServiceTest {

    @TempDir
    Path tempDir;

    private AnswerKeyService service;

    @BeforeEach
    void setUp() {
        service = new AnswerKeyService(
                new AssignmentDocumentService(new DocumentParserService()),
                mock(KnowledgeService.class), mock(DatabaseKnowledgeService.class),
                new ObjectMapper().findAndRegisterModules());
        ReflectionTestUtils.setField(service, "answerKeysPath", tempDir.resolve("answer-keys").toString());
        ReflectionTestUtils.setField(service, "javaKnowledgePath", tempDir.resolve("java-kb").toString());
        ReflectionTestUtils.setField(service, "databaseKnowledgePath", tempDir.resolve("db-kb").toString());
        service.init();
    }

    @Test
    void extractsTheTwoRealHundredPointRubrics() throws Exception {
        Dto.AnswerKeyProfile javaKey = service.importAnswerKey(file(Path.of(
                "专业课程作业批改案例", "Java程序设计", "Java-题目参考答案与评分标准.docx")),
                "auto", "");
        assertEquals("java", javaKey.courseType());
        assertEquals(List.of(15, 15, 40, 30), scores(javaKey));
        assertEquals(100, javaKey.maxScore());
        assertTrue(javaKey.questions().stream().noneMatch(Dto.AnswerKeyQuestion::scoreInferred));

        Dto.AnswerKeyProfile databaseKey = service.importAnswerKey(file(Path.of(
                "专业课程作业批改案例", "数据库原理", "DB-作业参考答案与评分标准.docx")),
                "auto", "");
        assertEquals("database", databaseKey.courseType());
        assertEquals(List.of(10, 30, 30, 30), scores(databaseKey));
        assertEquals(100, databaseKey.maxScore());
        List<String> referenceImageNames = databaseKey.questions().stream()
                .flatMap(question -> question.referenceImages().stream())
                .map(Dto.QuestionImage::fileName).toList();
        assertTrue(referenceImageNames.containsAll(List.of("image4.png", "image5.png")),
                () -> "应同时解析 DrawingML 公式图和 VML 查询结果图，实际为 " + referenceImageNames);
    }

    @Test
    void infersMissingScoresAndAllowsTeacherCorrectionOnlyAtTotalOneHundred() throws Exception {
        Dto.AnswerKeyProfile key = service.importAnswerKey(
                generatedPartialRubric(), "java", "缺失分值测试");
        assertEquals(List.of(60, 40), scores(key));
        assertFalse(key.questions().get(0).scoreInferred());
        assertTrue(key.questions().get(1).scoreInferred());

        Dto.AnswerKeyProfile updated = service.updateScores(key.id(),
                new Dto.AnswerKeyScoreUpdate(List.of(
                        new Dto.AnswerKeyScoreItem(1, 55),
                        new Dto.AnswerKeyScoreItem(2, 45))));
        assertEquals(List.of(55, 45), scores(updated));
        assertTrue(updated.questions().stream().noneMatch(Dto.AnswerKeyQuestion::scoreInferred));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateScores(key.id(), new Dto.AnswerKeyScoreUpdate(List.of(
                        new Dto.AnswerKeyScoreItem(1, 50),
                        new Dto.AnswerKeyScoreItem(2, 40)))));
        assertTrue(error.getMessage().contains("必须为 100"));
    }

    private List<Integer> scores(Dto.AnswerKeyProfile profile) {
        return profile.questions().stream().map(Dto.AnswerKeyQuestion::maxScore).toList();
    }

    private MockMultipartFile file(Path path) throws Exception {
        return new MockMultipartFile(
                "file", path.getFileName().toString(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Files.readAllBytes(path));
    }

    private MockMultipartFile generatedPartialRubric() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("【第1题】第一题");
            document.createParagraph().createRun().setText("{60分，按要点评分}");
            document.createParagraph().createRun().setText("答：答案一");
            document.createParagraph().createRun().setText("【第2题】第二题");
            document.createParagraph().createRun().setText("答：答案二");
            document.write(output);
            return new MockMultipartFile(
                    "file", "部分分值参考答案.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    output.toByteArray());
        }
    }
}
