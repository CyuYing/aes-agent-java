package com.aes.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssignmentDocumentServiceTest {

    private final AssignmentDocumentService service =
            new AssignmentDocumentService(new DocumentParserService());

    @Test
    void parsesAllJavaSamplesIncludingACompletelyBlankFourthAnswer() throws Exception {
        String[] names = {"Java-作业样本1.docx", "Java-作业样本2.docx", "Java-作业样本3.docx"};
        String[] students = {"张三", "李四", "王五"};
        String[] ids = {"j1111", "J2222", "J3333"};
        for (int i = 0; i < names.length; i++) {
            var parsed = service.parse(file(Path.of(
                    "专业课程作业批改案例", "Java程序设计", names[i])));
            assertEquals(4, parsed.questions().size(), names[i]);
            assertEquals(students[i], parsed.student().name());
            assertEquals(ids[i], parsed.student().studentId());
            assertEquals("计算1111", parsed.student().className());
            assertEquals("java", service.detectCourseType(
                    file(Path.of("专业课程作业批改案例", "Java程序设计", names[i])),
                    parsed.questions()));
        }
    }

    @Test
    void parsesAllDatabaseSamplesAndTheirStudentMetadata() throws Exception {
        String[] names = {"DB-作业样本1.docx", "DB-作业样本2.docx", "DB-作业样本3.docx"};
        String[] students = {"张三", "李四", "王五"};
        for (int i = 0; i < names.length; i++) {
            var parsed = service.parse(file(Path.of(
                    "专业课程作业批改案例", "数据库原理", names[i])));
            assertEquals(4, parsed.questions().size(), names[i]);
            assertEquals(students[i], parsed.student().name());
            assertEquals("计算1111", parsed.student().className());
            assertEquals("database", service.detectCourseType(
                    file(Path.of("专业课程作业批改案例", "数据库原理", names[i])),
                    parsed.questions()));
            if (i == 0) {
                assertTrue(parsed.questions().stream()
                        .flatMap(question -> question.images().stream())
                        .anyMatch(image -> "image4.png".equals(image.fileName())));
            } else if (i == 1) {
                assertTrue(parsed.questions().stream()
                        .flatMap(question -> question.images().stream())
                        .anyMatch(image -> "image4.png".equals(image.fileName())));
            }
        }
    }

    private MockMultipartFile file(Path path) throws Exception {
        return new MockMultipartFile(
                "file", path.getFileName().toString(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Files.readAllBytes(path));
    }
}
