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
     * Number of assessments that are past their planned end date and not in the configured
     * completed status
     */
    private long pastDueCount;

    /**
     * Count of assessments grouped by status, using the statuses the workflow configures.
     */
    private Map<String, Long> statusCounts;
}
