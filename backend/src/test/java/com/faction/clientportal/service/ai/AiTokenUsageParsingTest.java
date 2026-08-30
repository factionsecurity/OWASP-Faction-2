package com.faction.clientportal.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two wire formats report token usage under different field names. Getting these
 * wrong is silent — the chart simply reads zero — so both shapes are pinned here.
 */
class AiTokenUsageParsingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw).path("usage");
    }

    @Test
    void openAiFormat_readsPromptAndCompletionTokens() throws Exception {
        AiTokenUsage usage = AiChatClient.openAiUsage(json(
                "{\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":350,\"total_tokens\":1550}}"));

        assertThat(usage.inputTokens()).isEqualTo(1200);
        assertThat(usage.outputTokens()).isEqualTo(350);
        assertThat(usage.total()).isEqualTo(1550);
    }

    @Test
    void anthropicFormat_readsInputAndOutputTokens() throws Exception {
        AiTokenUsage usage = AiChatClient.anthropicUsage(json(
                "{\"usage\":{\"input_tokens\":900,\"output_tokens\":120}}"));

        assertThat(usage.inputTokens()).isEqualTo(900);
        assertThat(usage.outputTokens()).isEqualTo(120);
    }

    @Test
    void anthropicFormat_countsCachedInputWhichIsReportedSeparately() throws Exception {
        AiTokenUsage usage = AiChatClient.anthropicUsage(json(
                "{\"usage\":{\"input_tokens\":100,\"cache_creation_input_tokens\":2000,"
                        + "\"cache_read_input_tokens\":500,\"output_tokens\":80}}"));

        // Cached input is billed but excluded from input_tokens — dropping it under-reports.
        assertThat(usage.inputTokens()).isEqualTo(2600);
        assertThat(usage.outputTokens()).isEqualTo(80);
    }

    @Test
    void missingUsageBlock_readsAsZeroRatherThanFailing() throws Exception {
        assertThat(AiChatClient.openAiUsage(json("{}")).total()).isZero();
        assertThat(AiChatClient.anthropicUsage(json("{}")).total()).isZero();
    }

    @Test
    void usageAccumulatesAcrossTheToolLoop() {
        AiTokenUsage total = AiTokenUsage.NONE
                .plus(new AiTokenUsage(100, 20))
                .plus(new AiTokenUsage(400, 60))
                .plus(null);

        assertThat(total.inputTokens()).isEqualTo(500);
        assertThat(total.outputTokens()).isEqualTo(80);
        assertThat(total.total()).isEqualTo(580);
    }
}
