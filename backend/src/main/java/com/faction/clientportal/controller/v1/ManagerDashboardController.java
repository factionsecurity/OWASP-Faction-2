package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.ManagerDashboardAssessmentDto;
import com.faction.clientportal.util.PageableUtil;
import com.faction.clientportal.util.PageableUtil.SortField;
import com.faction.clientportal.dto.ManagerDashboardFilters;
import com.faction.clientportal.dto.ManagerDashboardStatsDto;
import com.faction.clientportal.dto.ManagerDashboardSummaryDto;
import com.faction.clientportal.dto.ManagerDashboardVulnerabilityDto;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.service.ManagerDashboardService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Manager dashboard: org-wide assessment/vulnerability reporting. Every
 * endpoint is gated exclusively by {@code manager_dashboard:read:all} —
 * deliberately independent of the assessments/vulnerabilities permissions.
 */
@RestController
@RequestMapping("/api/v1/manager-dashboard")
@RequiredArgsConstructor
@Tag(name = "Manager Dashboard", description = "Org-wide assessment and vulnerability reporting")
@SecurityRequirement(name = "bearerAuth")
public class ManagerDashboardController {

    private final ManagerDashboardService managerDashboardService;

    /**
     * Assessments-tab columns → the sort keys the assessment search query whitelists. Absent by
     * design: Team and Assessors (resolved from the assessors' JSONB id list after the page is
     * fetched) and Findings (per-severity counts aggregated per row) — none is a column to order by.
     */
    private static final Map<String, SortField> ASSESSMENT_SORTABLE_FIELDS = Map.of(
            "appId", SortField.text("appId"),
            "name", SortField.text("name"),
            "assessmentTypeName", SortField.text("assessmentTypeName"),
            "startDate", SortField.value("startDate"),
            "plannedEndDate", SortField.value("plannedEndDate"),
            "completedDate", SortField.value("completedDate"),
            "status", SortField.text("status"));

    private static final Sort DEFAULT_ASSESSMENT_SORT = Sort.by(Sort.Direction.DESC, "startDate");

    /** Vulnerabilities-tab columns; the service orders these in memory over the collected rows. */
    private static final Map<String, SortField> VULN_SORTABLE_FIELDS = Map.ofEntries(
            Map.entry("name", SortField.text("name")),
            Map.entry("assessmentName", SortField.text("assessmentName")),
            Map.entry("appId", SortField.text("appId")),
            Map.entry("severity", SortField.value("severity")),
            Map.entry("cvssScore", SortField.value("cvssScore")),
            Map.entry("categoryName", SortField.text("categoryName")),
            Map.entry("openedAt", SortField.value("openedAt")),
            Map.entry("closedAt", SortField.value("closedAt")),
            Map.entry("status", SortField.text("status")),
            Map.entry("trackingId", SortField.text("trackingId")));

    @GetMapping("/summary")
    @RequiresPermission(Permission.MANAGER_DASHBOARD_READ_ALL)
    @Operation(summary = "Global stats-card counts",
            description = "Completed assessments and opened vulnerabilities per rolling period (week/month/year/all-time). Unaffected by filters.")
    public ResponseEntity<JsonApiResponse<ManagerDashboardSummaryDto>> getSummary() {
        return ResponseUtil.success(managerDashboardService.getSummary());
    }

    @GetMapping("/stats")
    @RequiresPermission(Permission.MANAGER_DASHBOARD_READ_ALL)
    @Operation(summary = "Filtered breakdown statistics",
            description = "Severity, status, and completed-by-assessor breakdowns over the filtered assessment set")
    public ResponseEntity<JsonApiResponse<ManagerDashboardStatsDto>> getStats(
            ManagerDashboardFilterParams params,
            Authentication authentication) {
        return ResponseUtil.success(
                managerDashboardService.getStats(params.toFilters(), authentication));
    }

    @GetMapping("/assessments")
    @RequiresPermission(Permission.MANAGER_DASHBOARD_READ_ALL)
    @Operation(summary = "Filtered assessments (paginated)",
            description = "The assessments tab: filtered assessment rows annotated with assessor team names")
    public ResponseEntity<JsonApiResponse<List<ManagerDashboardAssessmentDto>>> searchAssessments(
            ManagerDashboardFilterParams params,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "startDate,desc") String sort,
            Authentication authentication) {
        Page<ManagerDashboardAssessmentDto> result = managerDashboardService.searchAssessments(
                params.toFilters(),
                PageableUtil.of(page, size, sort, DEFAULT_ASSESSMENT_SORT, ASSESSMENT_SORTABLE_FIELDS),
                authentication);
        return ResponseUtil.paginated(result);
    }

    @GetMapping("/vulnerabilities")
    @RequiresPermission(Permission.MANAGER_DASHBOARD_READ_ALL)
    @Operation(summary = "Filtered cross-assessment vulnerabilities (paginated)",
            description = "The vulnerabilities tab: every opened vulnerability (within the date range) across the filtered assessment set")
    public ResponseEntity<JsonApiResponse<List<ManagerDashboardVulnerabilityDto>>> searchVulnerabilities(
            ManagerDashboardFilterParams params,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            Authentication authentication) {
        Pageable pageable = PageableUtil.of(page, size, sort, Sort.unsorted(), VULN_SORTABLE_FIELDS);
        Page<ManagerDashboardVulnerabilityDto> result = managerDashboardService.searchVulnerabilities(
                params.toFilters(), pageable, authentication);
        return ResponseUtil.paginated(result);
    }

    @GetMapping("/vulnerabilities/{id}")
    @RequiresPermission(Permission.MANAGER_DASHBOARD_READ_ALL)
    @Operation(summary = "Full vulnerability detail",
            description = "One vulnerability with its parent assessment, for the dashboard's detail panel")
    public ResponseEntity<JsonApiResponse<com.faction.clientportal.dto.ManagerDashboardVulnerabilityDetailDto>> getVulnerabilityDetail(
            @org.springframework.web.bind.annotation.PathVariable String id) {
        return ResponseUtil.success(managerDashboardService.getVulnerabilityDetail(id));
    }

    @GetMapping(value = "/export/assessments.csv", produces = MediaType.TEXT_PLAIN_VALUE)
    @RequiresPermission(Permission.MANAGER_DASHBOARD_READ_ALL)
    @Operation(summary = "Export filtered assessments to CSV")
    public ResponseEntity<String> exportAssessmentsCsv(
            ManagerDashboardFilterParams params,
            Authentication authentication) {
        String csv = managerDashboardService.exportAssessmentsCsv(params.toFilters(), authentication);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=manager-dashboard-assessments.csv")
                .body(csv);
    }

    @GetMapping(value = "/export/vulnerabilities.csv", produces = MediaType.TEXT_PLAIN_VALUE)
    @RequiresPermission(Permission.MANAGER_DASHBOARD_READ_ALL)
    @Operation(summary = "Export filtered cross-assessment vulnerabilities to CSV")
    public ResponseEntity<String> exportVulnerabilitiesCsv(
            ManagerDashboardFilterParams params,
            Authentication authentication) {
        String csv = managerDashboardService.exportVulnerabilitiesCsv(params.toFilters(), authentication);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=manager-dashboard-vulnerabilities.csv")
                .body(csv);
    }

    private Pageable toPageable(int page, int size, String sort) {
        return PageableUtil.of(page, size, sort);
    }

    /**
     * The shared filter query params, bound once per endpoint via Spring's
     * setter-based binding for non-annotated method parameters.
     */
    public static class ManagerDashboardFilterParams {
        private String search;
        private String applicationId;
        private String assessmentTypeId;
        private String assessorId;
        private String status;
        private String teamId;
        private String campaignId;
        private List<VulnerabilitySeverity> severities;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime startDateFrom;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime startDateTo;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime endDateFrom;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime endDateTo;
        private Boolean showCompleted;

        public ManagerDashboardFilters toFilters() {
            return ManagerDashboardFilters.builder()
                    .search(search)
                    .applicationId(applicationId)
                    .assessmentTypeId(assessmentTypeId)
                    .assessorId(assessorId)
                    .status(status)
                    .teamId(teamId)
                    .campaignId(campaignId)
                    .severities(severities)
                    .startDateFrom(startDateFrom)
                    .startDateTo(startDateTo)
                    .endDateFrom(endDateFrom)
                    .endDateTo(endDateTo)
                    .showCompleted(showCompleted)
                    .build();
        }

        public void setSearch(String search) { this.search = search; }
        public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
        public void setAssessmentTypeId(String assessmentTypeId) { this.assessmentTypeId = assessmentTypeId; }
        public void setAssessorId(String assessorId) { this.assessorId = assessorId; }
        public void setStatus(String status) { this.status = status; }
        public void setTeamId(String teamId) { this.teamId = teamId; }
        public void setCampaignId(String campaignId) { this.campaignId = campaignId; }
        public void setSeverities(List<VulnerabilitySeverity> severities) { this.severities = severities; }
        public void setStartDateFrom(LocalDateTime startDateFrom) { this.startDateFrom = startDateFrom; }
        public void setStartDateTo(LocalDateTime startDateTo) { this.startDateTo = startDateTo; }
        public void setEndDateFrom(LocalDateTime endDateFrom) { this.endDateFrom = endDateFrom; }
        public void setEndDateTo(LocalDateTime endDateTo) { this.endDateTo = endDateTo; }
        public void setShowCompleted(Boolean showCompleted) { this.showCompleted = showCompleted; }
    }
}
