package com.storagehub.service.support.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.storagehub.config.SupportAiProperties;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@EnabledIfEnvironmentVariable(named = "RUN_GEMINI_SUPPORT_EVALUATION", matches = "true")
class GeminiSupportLiveEvaluationTests {
    private static GeminiSupportAiProvider provider;

    @BeforeAll
    static void configure() throws IOException {
        var properties = new SupportAiProperties();
        properties.setEnabled(true);
        properties.setApiKey(System.getenv("STORAGEHUB_AI_API_KEY"));
        properties.setModel(System.getenv("STORAGEHUB_AI_MODEL"));
        String timeout = System.getenv("STORAGEHUB_AI_TIMEOUT_MS");
        if (timeout != null && !timeout.isBlank()) properties.setTimeoutMs(Integer.parseInt(timeout));
        var mapper = SupportAssistantEvaluationTests.MAPPER;
        provider = new GeminiSupportAiProvider(properties, mapper, new SupportPublicKnowledge(mapper));
        provider.requireAvailable();
    }

    static Stream<SupportAssistantEvaluationTests.Example> geminiCases() throws IOException {
        String retry = System.getProperty("supportAiEvaluationCases", "");
        Set<String> selected = Set.of(retry.split(","));
        return SupportAssistantEvaluationTests.cases().filter(example -> example.route().equals("gemini"))
            .filter(example -> retry.isBlank() || selected.contains(example.id()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("geminiCases")
    void selectsApprovedSources(SupportAssistantEvaluationTests.Example example) {
        var answer = provider.answer(List.of(new SupportAiProvider.Message("user", example.question())));
        assertThat(answer.truncated()).isFalse();
        assertThat(answer.sourceIds()).as(example.id()).containsExactlyInAnyOrderElementsOf(example.sources());
    }

    @Test
    void understandsCapabilityQuestionOutsideTheShortcutPatterns() {
        var reply = provider.answer(List.of(new SupportAiProvider.Message("user", "Bạn giúp gì được cho tôi?")));
        assertThat(reply.action()).isEqualTo(SupportAiProvider.Action.CAPABILITIES);
    }

    @Test
    void writesAnAnswerGroundedInThePaymentGuide() {
        var reply = provider.answer(List.of(new SupportAiProvider.Message("user", "Xem lại tiền đã nộp ở đâu?")));
        assertThat(reply.action()).isEqualTo(SupportAiProvider.Action.ANSWER);
        assertThat(reply.sourceIds()).containsExactly("faq-payment-method");
        assertThat(reply.text()).contains("Lịch sử thanh toán");
    }
}
