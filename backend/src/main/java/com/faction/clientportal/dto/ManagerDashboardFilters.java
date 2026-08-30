package com.faction.clientportal.dto;

import com.faction.clientportal.model.VulnerabilitySeverity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The shared filter set every manager dashboard endpoint accepts
 * (stats, assessments tab, vulnerabilities tab, both CSV exports).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManagerDashboardFilters {
    private String search;
    private String applicationId;
    private String assessmentTypeId;
    private String assessorId;
    private String status;
    private String teamId;
    private String campaignId;
    private List<VulnerabilitySeverity> severities;
    private LocalDateTime startDateFrom;
    private LocalDateTime startDateTo;
    private LocalDateTime endDateFrom;
    private LocalDateTime endDateTo;
    private Boolean showCompleted;
}
