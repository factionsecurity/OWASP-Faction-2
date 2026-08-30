package com.faction.clientportal.service.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiOutputCleanupTest {

    @Test
    void cleanOutput_stripsCodeFences() {
        assertThat(AiPromptExecutionService.cleanOutput("```html\n<p>Hello</p>\n```"))
                .isEqualTo("<p>Hello</p>");
    }

    @Test
    void cleanOutput_stripsDocumentWrappers() {
        assertThat(AiPromptExecutionService.cleanOutput(
                "<!DOCTYPE html><html><head><title>x</title></head><body><p>Hi</p></body></html>"))
                .isEqualTo("<p>Hi</p>");
    }

    @Test
    void cleanOutput_wrapsPlainTextInParagraphs() {
        assertThat(AiPromptExecutionService.cleanOutput("First para.\n\nSecond para."))
                .isEqualTo("<p>First para.</p><p>Second para.</p>");
    }

    @Test
    void cleanTitle_stripsQuotesHtmlAndTrailingPeriod() {
        assertThat(AiPromptExecutionService.cleanTitle("\"<strong>SQL Injection in Login Form</strong>.\""))
                .isEqualTo("SQL Injection in Login Form");
    }

    @Test
    void cleanTitle_takesOnlyFirstLine() {
        assertThat(AiPromptExecutionService.cleanTitle("Great Title\nAnd some explanation"))
                .isEqualTo("Great Title");
    }
}
