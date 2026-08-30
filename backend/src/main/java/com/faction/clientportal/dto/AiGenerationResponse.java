package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of an AI generation. Failures are reported with success=false rather
 * than HTTP errors so the editor can show the message inline.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiGenerationResponse {
    private boolean success;
    /** Generated HTML fragment when success=true */
    private String content;
    /** Error description when success=false */
    private String message;

    public static AiGenerationResponse ok(String content) {
        return AiGenerationResponse.builder().success(true).content(content).build();
    }

    public static AiGenerationResponse failure(String message) {
        return AiGenerationResponse.builder().success(false).message(message).build();
    }
}
