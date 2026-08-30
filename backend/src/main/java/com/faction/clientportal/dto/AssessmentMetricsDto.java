package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * DTO for assessment metrics/dashboard statistics
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentMetricsDto {
    /**
     * Total number of assessments
     */
    private long totalCount;

    /**
     * Number of assessments in DRAFT status
     */
    private long draftCount;

    /**
     * Number of assessments in IN_PROGRESS status
     */
    private long inProgressCount;

    /**
     * Number of assessments in ON_HOLD status
     */
    private long onHoldCount;

    /**
     * Number of assessments in PENDING_REVIEW status
     */
    private long pendingReviewCount;

    /**
     * Number of assessments in COMPLETED status
     */
    private long completedCount;

    /**
     * Number of assessments in APPROVED status
     */
    private long approvedCount;

    /**
     * Number of assessments in ARCHIVED status
     */
    private long archivedCount;

    /**
     * Number of assessments that are past their planned end date
     * and not yet completed/approved/archived
     */
    private long pastDueCount;

    /**
     * Count of assessments grouped by status string.
     * Includes all status values (legacy and custom).
     */
    private Map<String, Long> statusCounts;
}
