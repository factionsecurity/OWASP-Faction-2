package com.faction.clientportal.dto;

import lombok.Getter;

import java.time.LocalDate;

/** One day's AI token totals, summed across every user, provider and model. */
@Getter
public class AiTokenUsageDayDto {

    private final LocalDate date;
    private final long inputTokens;
    private final long outputTokens;
    private final long totalTokens;
    private final long requests;

    /** Constructor shape is fixed by the JPQL projection in AiTokenUsageDayRepository. */
    public AiTokenUsageDayDto(LocalDate date, Long inputTokens, Long outputTokens, Long requests) {
        this.date = date;
        this.inputTokens = nullToZero(inputTokens);
        this.outputTokens = nullToZero(outputTokens);
        this.totalTokens = this.inputTokens + this.outputTokens;
        this.requests = nullToZero(requests);
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }
}
