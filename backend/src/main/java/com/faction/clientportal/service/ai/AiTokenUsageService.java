package com.faction.clientportal.service.ai;

import com.faction.clientportal.dto.AiTokenUsageDayDto;
import com.faction.clientportal.repository.AiTokenUsageDayRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Maintains the daily AI token ledger. Unlike the AI request log this is not
 * behind a toggle — token history cannot be reconstructed after the fact, so it is always
 * recorded. Writes are best-effort and never fail an AI request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiTokenUsageService {

    private static final String UNKNOWN = "unknown";

    private final AiTokenUsageDayRepository repository;

    /**
     * Attributes one AI request's tokens to today. Requests that reported no tokens — a call
     * that failed before reaching the provider, or a provider that omits a usage block — are
     * skipped, so the ledger only ever counts consumption it can actually stand behind.
     */
    public void record(String username, String providerName, String model, AiTokenUsage usage) {
        if (usage == null || usage.total() <= 0) {
            return;
        }
        try {
            repository.addUsage(UUID.randomUUID().toString(), LocalDate.now(),
                    orUnknown(username), orUnknown(providerName), orUnknown(model),
                    usage.inputTokens(), usage.outputTokens());
        } catch (Exception e) {
            log.warn("Failed to record AI token usage: {}", e.getMessage());
        }
    }

    /** Daily totals for an inclusive date range, oldest first. */
    public List<AiTokenUsageDayDto> dailyTotals(LocalDate from, LocalDate to) {
        return repository.dailyTotals(from, to);
    }

    /** Keeps the ledger's unique key intact — NULLs would never collide, splitting a day's row. */
    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
