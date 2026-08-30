package com.faction.clientportal.service.ai;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The open source answer: nothing is recorded.
 *
 * <p>Indistinguishable from an enterprise install with request logging switched off,
 * which is its default state — so this is the same path that build takes most of the time.
 */
@Service
public class NoOpAiCallObserver implements AiCallObserver {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public void record(CallContext ctx, String providerName, String model, boolean anonymizationEnabled,
                       String systemPrompt, List<AiChatMessage> messages, String rawResponse,
                       boolean success, String errorMessage, long durationMs, AiTokenUsage usage) {
        // Deliberately nothing.
    }
}
