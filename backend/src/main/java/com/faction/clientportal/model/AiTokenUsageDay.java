package com.faction.clientportal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Running token totals for one day, user, provider and model — the ledger the AI
 * usage chart is drawn from.
 *
 * <p>Deliberately separate from {@link AiRequestLog}: that table is an opt-in privacy
 * audit trail that is purged on a retention window, so budget history cannot live there.
 * This one is always written and never purged, and stays small because it holds a rolled-up
 * row per day rather than a row per request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_token_usage_daily",
        uniqueConstraints = @UniqueConstraint(name = "uk_ai_token_usage_day",
                columnNames = {"usage_date", "username", "provider_name", "model"}),
        indexes = @Index(name = "idx_ai_token_usage_date", columnList = "usage_date"))
public class AiTokenUsageDay {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    /** JWT username the request was attributed to; "unknown" when unauthenticated. */
    @Column(nullable = false)
    private String username;

    @Column(name = "provider_name", nullable = false)
    private String providerName;

    @Column(nullable = false)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    @Builder.Default
    private long inputTokens = 0;

    @Column(name = "output_tokens", nullable = false)
    @Builder.Default
    private long outputTokens = 0;

    /** Provider calls that reported billable tokens, not logical AI requests. */
    @Column(name = "request_count", nullable = false)
    @Builder.Default
    private long requestCount = 0;
}
