package com.aes.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChoiceGradingServiceTest {

    private final ChoiceGradingService service = new ChoiceGradingService();

    @Test
    void normalizesHarmlessFormattingButKeepsDeterministicComparison() {
        assertThat(service.normalizeAnswer("答案为：a， c")).isEqualTo("AC");
        assertThat(service.compare("C / A", "A,C").matched()).isTrue();
        assertThat(service.compare("B", "A").matched()).isFalse();
    }

    @Test
    void missingCorrectAnswerNeverReceivesCredit() {
        ChoiceGradingService.ChoiceDecision decision = service.compare("A", "");

        assertThat(decision.matched()).isFalse();
        assertThat(decision.normalizedCorrectAnswer()).isEmpty();
    }
}
