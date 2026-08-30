package com.faction.clientportal.service.ai;

/**
 * Token counts the provider reported for a call. One logical AI request can span
 * several provider calls (the tool-use loop), so these accumulate with {@link #plus}.
 */
public record AiTokenUsage(int inputTokens, int outputTokens) {

    public static final AiTokenUsage NONE = new AiTokenUsage(0, 0);

    public AiTokenUsage plus(AiTokenUsage other) {
        return other == null ? this
                : new AiTokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens);
    }

    public int total() {
        return inputTokens + outputTokens;
    }
}
