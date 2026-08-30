package com.faction.clientportal.dto;

import com.faction.clientportal.model.VulnerabilitySeverity;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row of the interleaved remediation queue — a vulnerability at/past its SLA warning threshold or
 * an open retest, flattened to a common shape and enriched with the joined application / organization
 * names, so the Remediation page renders it without any client-side SLA math or per-row lookups.
 *
 * <p>{@code type}-specific fields: {@code retestStatus}/{@code startDate}/{@code endDate} are set on
 * retest rows; {@code lastRetestStatus} (the most recent PASSED/FAILED retest of the vuln) is set on
 * vuln rows. {@code vulnerabilityStatus} is set on both — it is always the underlying vulnerability's
 * status. {@code dueDate} is the SLA deadline for a vuln and the scheduled end date for a retest
 * (null for a requested retest with no schedule yet).
 */
@Data
@Builder
public class RemediationRowDto {
    private String key;                 // stable table key: "vuln-<id>" / "retest-<id>"
    private String id;                  // the row entity's own id (retest id on retest rows, vuln id on vuln rows)
    private String type;                // "VULNERABILITY" | "RETEST"
    private String vulnerabilityId;
    private String vulnerabilityName;
    private VulnerabilitySeverity severity;
    private String assessmentId;
    private String applicationId;
    private String applicationName;
    private String organizationId;
    private String organizationName;
    private LocalDate dueDate;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private boolean urgent;
    private boolean warning;
    private String vulnerabilityStatus; // the vulnerability's workflow status, on both row types
    private String retestStatus;        // retest rows only
    private String lastRetestStatus;    // vuln rows only: "PASSED" | "FAILED"
}
