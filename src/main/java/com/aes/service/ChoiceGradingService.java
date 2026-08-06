package com.aes.service;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 选择题采用确定性的标准答案对比，不调用大语言模型。 */
@Service
public class ChoiceGradingService {

    private static final Pattern LETTER_ANSWER = Pattern.compile(
            "(?i)(?<![A-Z])([A-H](?:\\s*[,，、/;；\\s]?\\s*[A-H])*)(?![A-Z])");

    public ChoiceDecision compare(String studentAnswer, String correctAnswer) {
        String normalizedStudent = normalizeAnswer(studentAnswer);
        String normalizedCorrect = normalizeAnswer(correctAnswer);
        boolean matched = !normalizedCorrect.isBlank()
                && normalizedCorrect.equals(normalizedStudent);
        return new ChoiceDecision(
                normalizedStudent, normalizedCorrect, matched);
    }

    /**
     * 忽略全角/半角、大小写和无意义分隔符；多选答案按字母排序，
     * 但不会做语义猜测或近似匹配。
     */
    public String normalizeAnswer(String answer) {
        if (answer == null || answer.isBlank()) return "";
        String normalized = Normalizer.normalize(answer, Normalizer.Form.NFKC)
                .toUpperCase()
                .replaceFirst("^(?:标准)?答案(?:是|为)?[:：]?", "")
                .replaceFirst("^(?:选择|选项|选)[:：]?", "")
                .trim();

        Matcher matcher = LETTER_ANSWER.matcher(normalized);
        if (matcher.find()) {
            Set<Character> letters = new TreeSet<>();
            for (char value : matcher.group(1).toCharArray()) {
                if (value >= 'A' && value <= 'H') letters.add(value);
            }
            StringBuilder result = new StringBuilder();
            letters.forEach(result::append);
            return result.toString();
        }

        return normalized.replaceAll("[\\s,，、/;；.。:：]+", "");
    }

    public record ChoiceDecision(
            String normalizedStudentAnswer,
            String normalizedCorrectAnswer,
            boolean matched
    ) {}
}
