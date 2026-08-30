package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Breakdown statistics computed from the manager dashboard's currently
 * filtered assessment/vulnerability set. Severity colors are a frontend
 * concern (existing severity palette) and deliberately not included here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManagerDashboardStatsDto {

    /** Opened vulnerabilities by severity, over the filtered assessment set */
    private Map<String, Long> severityBreakdown;

    /** Assessments by status, over the filtered assessment set */
    private Map<String, Long> statusBreakdown;

    /** Completed assessments per assessor, sorted descending by count */
    private List<AssessorCompletedCount> completedByAssessor;

    private long totalVulnerabilities;
    private long totalAssessments;
    private long totalCompletedAssessments;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssessorCompletedCount {
        private String assessorId;
        private String assessorName;
        private long count;
    }
}
