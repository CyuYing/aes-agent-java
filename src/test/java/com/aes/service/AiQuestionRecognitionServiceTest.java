package com.aes.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiQuestionRecognitionServiceTest {

    @Test
    void refinesRuleBoundaryUsingValidatedOriginalLineNumbers() {
        ChatLanguageModel model = messages -> Response.from(AiMessage.from("""
                {"questionStartLines":[1,4],"confidence":0.94}
                """));
        AiQuestionRecognitionService service = new AiQuestionRecognitionService(
                model, new ObjectMapper());
        String text = "设计一个学生类\n学生作答：class Student {}\n\n"
                + "解释运行时多态\n学生作答：由实际对象决定";

        AiQuestionRecognitionService.Refinement result = service.refine(
                text, List.of(text), 2);

        assertThat(result.blocks()).containsExactly(
                "设计一个学生类\n学生作答：class Student {}",
                "解释运行时多态\n学生作答：由实际对象决定");
        assertThat(result.recognition().aiUsed()).isTrue();
        assertThat(result.recognition().method()).isEqualTo("ai-refined");
        assertThat(result.recognition().ruleQuestionCount()).isEqualTo(1);
        assertThat(result.recognition().finalQuestionCount()).isEqualTo(2);
    }

    @Test
    void fallsBackWhenModelReturnsAnOutOfRangeBoundary() {
        ChatLanguageModel model = messages -> Response.from(AiMessage.from("""
                {"questionStartLines":[1,999],"confidence":0.99}
                """));
        AiQuestionRecognitionService service = new AiQuestionRecognitionService(
                model, new ObjectMapper());
        String text = "第1题 编写程序\n学生作答：完成";

        AiQuestionRecognitionService.Refinement result = service.refine(
                text, List.of(text), 2);

        assertThat(result.blocks()).containsExactly(text);
        assertThat(result.recognition().aiUsed()).isFalse();
        assertThat(result.recognition().method()).isEqualTo("rule-fallback");
        assertThat(result.recognition().message()).contains("自动使用本地规则");
    }

    @Test
    void keepsRuleResultWhenAiConflictsWithAnAlreadyMatchingAnswerKeyCount() {
        ChatLanguageModel model = messages -> Response.from(AiMessage.from("""
                {"questionStartLines":[1],"confidence":0.99}
                """));
        AiQuestionRecognitionService service = new AiQuestionRecognitionService(
                model, new ObjectMapper());
        String text = "第1题 A\n答案 A\n第2题 B\n答案 B";
        List<String> ruleBlocks = List.of("第1题 A\n答案 A", "第2题 B\n答案 B");

        AiQuestionRecognitionService.Refinement result = service.refine(
                text, ruleBlocks, 2);

        assertThat(result.blocks()).isEqualTo(ruleBlocks);
        assertThat(result.recognition().aiUsed()).isFalse();
        assertThat(result.recognition().message()).contains("题数或置信度校验");
    }
}
