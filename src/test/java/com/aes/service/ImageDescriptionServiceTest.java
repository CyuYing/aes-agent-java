package com.aes.service;

import com.aes.model.Dto;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ImageDescriptionServiceTest {

    @Test
    void compactAnalysisDeduplicatesAndSendsOneUpscaledContactSheet() throws Exception {
        AtomicReference<List<ChatMessage>> request = new AtomicReference<>();
        ChatLanguageModel model = messages -> {
            request.set(messages);
            return Response.from(AiMessage.from("""
                    {"images":[
                      {"position":1,"description":"学生图"},
                      {"position":2,"description":"参考图"}
                    ],"comparison":"匹配度：高"}
                    """));
        };
        var service = new ImageDescriptionService(model, true, new ObjectMapper());
        String image = tinyPng();
        var duplicateStudentImages = List.of(
                picture("student-1", image, "student"),
                picture("student-2", image, "student"));
        var referenceImages = List.of(picture("reference-1", image, "reference"));
        var question = new Dto.QuestionEntry(
                3, "第3题", "识别关系代数符号", "", "", "image", "", duplicateStudentImages);

        ImageDescriptionService.ImageAnalysisBundle result =
                service.analyzeQuestionCompact(question, referenceImages);

        assertThat(result.analyses()).hasSize(2);
        assertThat(result.analyses()).extracting(Dto.ImageAnalysis::role)
                .containsExactly("student", "reference");
        assertThat(result.comparison()).isEqualTo("匹配度：高");
        UserMessage userMessage = (UserMessage) request.get().get(0);
        assertThat(userMessage.contents().stream().filter(ImageContent.class::isInstance))
                .hasSize(1);
        ImageContent contactSheet = (ImageContent) userMessage.contents().stream()
                .filter(ImageContent.class::isInstance).findFirst().orElseThrow();
        assertThat(contactSheet.image().mimeType()).isEqualTo("image/png");
    }

    private Dto.QuestionImage picture(String id, String data, String role) {
        return new Dto.QuestionImage(id, id + ".png", "image/png", data, role);
    }

    private String tinyPng() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                image.setRGB(x, y, (x + y) % 2 == 0 ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }
}
