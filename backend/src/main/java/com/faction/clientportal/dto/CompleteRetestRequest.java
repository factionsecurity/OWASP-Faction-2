package com.faction.clientportal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CompleteRetestRequest {
    @NotBlank
    private String result; // PASS or FAIL

    private String comment;

    /**
     * What a passing retest closes. Ignored on FAIL, and defaults to {@code RETEST_ONLY} when the
     * caller doesn't send it, so existing integrations keep their previous behaviour.
     *
     * <ul>
     *   <li>{@code RETEST_ONLY} — closes the retest and nothing else. The vulnerability stays open
     *       and keeps appearing in the remediation queue.
     *   <li>A configured remediation stage id (see the workflow config's {@code remediationStages})
     *       — records a stage completion. A non-terminal stage leaves the vulnerability open: the
     *       fix is confirmed there, not in production. The terminal (last configured) stage closes
     *       it outright (status Closed, {@code closedAt}).
     *   <li>Legacy values {@code DEVELOPMENT} / {@code STAGING} / {@code PRODUCTION} still work,
     *       mapping to the default stage ids (PRODUCTION → the current terminal stage).
     * </ul>
     */
    @Schema(description = "What a passing retest closes: RETEST_ONLY or a configured remediation stage id "
            + "(legacy DEVELOPMENT/STAGING/PRODUCTION accepted)",
            example = "production")
    private String closure;

    /** Revised ratings for the underlying vulnerability; null means unchanged. See UpdateRetestRequest. */
    private String severity;
    private String likelihood;
    private String impact;
}
