package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Global (unfiltered) stats-card counts for the manager dashboard:
 * completed assessments and opened vulnerabilities per rolling period.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManagerDashboardSummaryDto {

    private PeriodCounts completedAssessments;
    private PeriodCounts vulnerabilities;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PeriodCounts {
        private long week;
        private long month;
        private long year;
        private long allTime;
    }
}
