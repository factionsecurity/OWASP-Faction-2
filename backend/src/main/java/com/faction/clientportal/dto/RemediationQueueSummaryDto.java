package com.faction.clientportal.dto;

/**
 * The remediation queue broken into the buckets the Remediation Alerts badges show. The buckets
 * partition the queue — every open row is in exactly one — so they add up to {@code total}, which is
 * also the nav badge's number. Verified (PASSED / FAILED) retests are never counted: they are history,
 * not work.
 */
public record RemediationQueueSummaryDto(
        long total,
        /** Findings past their SLA due date. */
        long pastDue,
        /** Findings inside the SLA warning window, not yet due. */
        long dueSoon,
        long retestRequested,
        long retestScheduled,
        long retestInProgress
) {
    public static RemediationQueueSummaryDto empty() {
        return new RemediationQueueSummaryDto(0, 0, 0, 0, 0, 0);
    }
}
