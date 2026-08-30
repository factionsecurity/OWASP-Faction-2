package com.faction.clientportal.service.ai;

import java.util.List;

/**
 * Notified after every completed AI call, successful or not.
 *
 * <p>The seam that keeps prompt and completion auditing out of the open source build
 * without the calling code having to know it. Token <em>accounting</em> deliberately does
 * not go through here — that stays wired directly to {@code AiTokenUsageService}, because
 * knowing what an install is spending is not a paid feature.
 *
 * <p>Implementations must not throw: an audit failure must never fail the AI call that
 * was otherwise fine.
 */
public interface AiCallObserver {

    /** Which user and action a call belongs to. */
    record CallContext(String username, String action, String assessmentId,
                       String vulnerabilityId, String promptId, String promptName) {}

    /** Whether anything is actually being recorded, for callers that can skip preparation. */
    boolean isEnabled();

    void record(CallContext ctx, String providerName, String model, boolean anonymizationEnabled,
                String systemPrompt, List<AiChatMessage> messages, String rawResponse,
                boolean success, String errorMessage, long durationMs, AiTokenUsage usage);
}
