package com.aes.service;

import com.aes.model.Dto;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentParserServiceTest {

    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZQmcAAAAASUVORK5CYII=");

    private final DocumentParserService service = new DocumentParserService();

    @Test
    void splitsEveryExplicitQuestionAndDetectsChoiceAndProgrammingAnswers() {
        String text = """
                Java 作业
                姓名：张三

                第1题 单项选择题
                Java 源文件的扩展名是什么？
                A. .class
                B. .java
                C. .jar
                D. .xml
                学生答案：B

                【第2题】输出问候语
                编写程序输出 hello。
                答：
                ```java
                public class Main {
                    public static void main(String[] args) {
                        System.out.println("hello");
                    }
                }
                ```
                """;

        List<Dto.QuestionEntry> questions = service.parseText(text);

        assertThat(questions).hasSize(2);
        assertThat(questions.get(0).questionType()).isEqualTo("choice");
        assertThat(questions.get(0).studentAnswer()).isEqualTo("B");
        assertThat(questions.get(0).description()).contains("扩展名").doesNotContain("学生答案");
        assertThat(questions.get(1).questionType()).isEqualTo("programming");
        assertThat(questions.get(1).code()).contains("public class Main");
    }

    @Test
    void splitsSequentialNumericHeadings() {
        String text = """
                1. 第一小题
                写出 JVM 的全称。
                答：Java Virtual Machine

                2、第二小题
                说明 JDK 与 JRE 的关系。
                答：JDK 包含 JRE。
                """;

        List<Dto.QuestionEntry> questions = service.parseText(text);

        assertThat(questions).hasSize(2);
        assertThat(questions).extracting(Dto.QuestionEntry::index).containsExactly(1, 2);
        assertThat(questions.get(0).studentAnswer()).isEqualTo("Java Virtual Machine");
        assertThat(questions.get(1).description()).contains("JDK 与 JRE");
    }

    @Test
    void extractsAndAssignsDocxImageToStudentAnswer() throws Exception {
        byte[] docx;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("第1题 图片作答题");
            document.createParagraph().createRun().setText("请画出程序执行流程。");
            XWPFParagraph answer = document.createParagraph();
            answer.createRun().setText("学生答案：");
            answer.createRun().addPicture(
                    new ByteArrayInputStream(ONE_PIXEL_PNG),
                    Document.PICTURE_TYPE_PNG,
                    "answer.png",
                    Units.toEMU(20), Units.toEMU(20));
            document.write(output);
            docx = output.toByteArray();
        }

        MockMultipartFile file = new MockMultipartFile(
                "file", "homework.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docx);

        List<Dto.QuestionEntry> questions = service.parseDocx(file);

        assertThat(questions).hasSize(1);
        assertThat(questions.get(0).questionType()).isEqualTo("image");
        assertThat(questions.get(0).images()).hasSize(1);
        assertThat(questions.get(0).images().get(0).role()).isEqualTo("student");
        assertThat(questions.get(0).images().get(0).dataBase64()).isNotBlank();
    }
}
