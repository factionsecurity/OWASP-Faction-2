package com.faction.clientportal.service;

import com.faction.clientportal.dto.AssessmentDto;
import com.faction.clientportal.dto.ManagerDashboardAssessmentDto;
import com.faction.clientportal.dto.ManagerDashboardFilters;
import com.faction.clientportal.dto.ManagerDashboardStatsDto;
import com.faction.clientportal.dto.ManagerDashboardSummaryDto;
import com.faction.clientportal.dto.ManagerDashboardVulnerabilityDetailDto;
import com.faction.clientportal.dto.ManagerDashboardVulnerabilityDto;
import com.faction.clientportal.dto.VulnerabilityDto;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.Team;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilityCategory;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.TeamRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityCategoryRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Backs the manager dashboard: global stats-card counts, filtered breakdown
 * statistics, the cross-assessment vulnerabilities view, and CSV exports.
 * Assessment filtering delegates to the shared pipeline in
 * {@link AssessmentService#searchAssessmentsAdvanced}.
 */
@Service
@RequiredArgsConstructor
public class ManagerDashboardService {

    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);

    private final AssessmentService assessmentService;
    private final AssessmentRepository assessmentRepository;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final VulnerabilityCategoryRepository vulnerabilityCategoryRepository;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final AssessmentWorkflowConfigService workflowConfigService;

    /**
     * Global stats-card counts (rolling periods), unaffected by the filter form.
     */
    public ManagerDashboardSummaryDto getSummary() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime weekAgo = now.minusDays(7);
        LocalDateTime monthAgo = now.minusMonths(1);
        LocalDateTime yearAgo = now.minusYears(1);

        return ManagerDashboardSummaryDto.builder()
                .completedAssessments(ManagerDashboardSummaryDto.PeriodCounts.builder()
                        .week(assessmentRepository.countByCompletedDateBetweenAndDeletedAtIsNull(weekAgo, now))
                        .month(assessmentRepository.countByCompletedDateBetweenAndDeletedAtIsNull(monthAgo, now))
                        .year(assessmentRepository.countByCompletedDateBetweenAndDeletedAtIsNull(yearAgo, now))
                        .allTime(assessmentRepository.countByCompletedDateBetweenAndDeletedAtIsNull(EPOCH, now))
                        .build())
                .vulnerabilities(ManagerDashboardSummaryDto.PeriodCounts.builder()
                        .week(vulnerabilityRepository.countByOpenedAtBetweenAndDeletedAtIsNull(weekAgo, now))
                        .month(vulnerabilityRepository.countByOpenedAtBetweenAndDeletedAtIsNull(monthAgo, now))
                        .year(vulnerabilityRepository.countByOpenedAtBetweenAndDeletedAtIsNull(yearAgo, now))
                        .allTime(vulnerabilityRepository.countByOpenedAtBetweenAndDeletedAtIsNull(EPOCH, now))
                        .build())
                .build();
    }

    /**
     * Assessments tab: the filtered assessment page, each row annotated with
     * the names of the teams its assessors belong to.
     */
    public Page<ManagerDashboardAssessmentDto> searchAssessments(
            ManagerDashboardFilters filters, Pageable pageable, Authentication authentication) {
        Page<AssessmentDto> page = fetchAssessments(filters, pageable, authentication);

        // Resolve team names for the page in bulk: assessors -> users -> teamIds -> team names
        Set<String> assessorIds = page.getContent().stream()
                .filter(a -> a.getAssessorIds() != null)
                .flatMap(a -> a.getAssessorIds().stream())
                .collect(Collectors.toSet());
        Map<String, User> usersById = userRepository.findAllById(assessorIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<String, String> teamNamesById = teamRepository.findAll().stream()
                .collect(Collectors.toMap(Team::getId, Team::getName));

        return page.map(a -> ManagerDashboardAssessmentDto.builder()
                .assessment(a)
                .teamNames(resolveTeamNames(a, usersById, teamNamesById))
                .build());
    }

    /**
     * Vulnerabilities tab: every opened vulnerability (within the date range,
     * matching the severity filter) across the filtered assessment set.
     */
    public Page<ManagerDashboardVulnerabilityDto> searchVulnerabilities(
            ManagerDashboardFilters filters, Pageable pageable, Authentication authentication) {
        List<ManagerDashboardVulnerabilityDto> rows = collectVulnerabilities(filters, authentication);
        rows = sortVulnerabilityRows(rows, pageable);

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), rows.size());
        List<ManagerDashboardVulnerabilityDto> pageContent =
                start < rows.size() ? rows.subList(start, end) : Collections.emptyList();
        return new PageImpl<>(pageContent, pageable, rows.size());
    }

    /**
     * Every column this tab shows lives on the already-materialized row, so each is sortable with a
     * comparator — no query rewrite needed. Unsorted keeps {@code collectVulnerabilities}' newest-
     * opened-first order.
     */
    private static final Map<String, Comparator<ManagerDashboardVulnerabilityDto>> VULN_SORTS = Map.of(
            "name", comparingText(ManagerDashboardVulnerabilityDto::getName),
            "assessmentName", comparingText(ManagerDashboardVulnerabilityDto::getAssessmentName),
            "appId", comparingText(ManagerDashboardVulnerabilityDto::getAppId),
            "severity", Comparator.comparing(ManagerDashboardVulnerabilityDto::getSeverity,
                    Comparator.nullsLast(Comparator.naturalOrder())),
            "cvssScore", Comparator.comparing(ManagerDashboardVulnerabilityDto::getCvssScore,
                    Comparator.nullsLast(Comparator.naturalOrder())),
            "categoryName", comparingText(ManagerDashboardVulnerabilityDto::getCategoryName),
            "openedAt", Comparator.comparing(ManagerDashboardVulnerabilityDto::getOpenedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())),
            "closedAt", Comparator.comparing(ManagerDashboardVulnerabilityDto::getClosedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())),
            "status", comparingText(ManagerDashboardVulnerabilityDto::getStatus),
            "trackingId", comparingText(ManagerDashboardVulnerabilityDto::getTrackingId));

    private static Comparator<ManagerDashboardVulnerabilityDto> comparingText(
            Function<ManagerDashboardVulnerabilityDto, String> accessor) {
        return Comparator.comparing(accessor, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }

    /** Applies {@code pageable}'s sort to the collected rows before they are cut into a page. */
    private List<ManagerDashboardVulnerabilityDto> sortVulnerabilityRows(
            List<ManagerDashboardVulnerabilityDto> rows, Pageable pageable) {
        if (pageable.getSort().isUnsorted()) return rows;
        Sort.Order order = pageable.getSort().iterator().next();
        Comparator<ManagerDashboardVulnerabilityDto> comparator = VULN_SORTS.get(order.getProperty());
        if (comparator == null) return rows;

        // Tiebreak on id so paging over equal keys can't repeat or skip a row between requests.
        comparator = comparator.thenComparing(ManagerDashboardVulnerabilityDto::getId,
                Comparator.nullsLast(Comparator.naturalOrder()));
        List<ManagerDashboardVulnerabilityDto> sorted = new ArrayList<>(rows);
        sorted.sort(order.isDescending() ? comparator.reversed() : comparator);
        return sorted;
    }

    /**
     * Breakdown stats over the filtered set: opened vulnerabilities by severity,
     * assessments by status, completed assessments by assessor (sorted desc).
     */
    public ManagerDashboardStatsDto getStats(ManagerDashboardFilters filters, Authentication authentication) {
        List<AssessmentDto> assessments =
                fetchAssessments(filters, Pageable.unpaged(), authentication).getContent();
        List<ManagerDashboardVulnerabilityDto> vulnerabilities =
                collectVulnerabilities(assessments, filters);

        Map<String, Long> severityBreakdown = vulnerabilities.stream()
                .filter(v -> v.getSeverity() != null)
                .collect(Collectors.groupingBy(v -> v.getSeverity().name(),
                        LinkedHashMap::new, Collectors.counting()));

        Map<String, Long> statusBreakdown = assessments.stream()
                .filter(a -> a.getStatus() != null)
                .collect(Collectors.groupingBy(AssessmentDto::getStatus,
                        LinkedHashMap::new, Collectors.counting()));

        Map<String, Long> completedCounts = assessments.stream()
                .filter(a -> workflowConfigService.isCompletedStatus(a.getStatus()))
                .filter(a -> a.getAssessorIds() != null)
                .flatMap(a -> a.getAssessorIds().stream())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        Map<String, User> usersById = userRepository.findAllById(completedCounts.keySet()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<ManagerDashboardStatsDto.AssessorCompletedCount> completedByAssessor = completedCounts.entrySet().stream()
                .map(e -> ManagerDashboardStatsDto.AssessorCompletedCount.builder()
                        .assessorId(e.getKey())
                        .assessorName(displayName(usersById.get(e.getKey()), e.getKey()))
                        .count(e.getValue())
                        .build())
                .sorted(Comparator.comparingLong(ManagerDashboardStatsDto.AssessorCompletedCount::getCount).reversed())
                .collect(Collectors.toList());

        return ManagerDashboardStatsDto.builder()
                .severityBreakdown(severityBreakdown)
                .statusBreakdown(statusBreakdown)
                .completedByAssessor(completedByAssessor)
                .totalVulnerabilities(vulnerabilities.size())
                .totalAssessments(assessments.size())
                .totalCompletedAssessments(completedByAssessor.stream()
                        .mapToLong(ManagerDashboardStatsDto.AssessorCompletedCount::getCount).sum())
                .build();
    }

    /**
     * Full detail for one vulnerability plus its parent assessment, for the
     * dashboard's detail panel. Access control lives on the endpoint's
     * manager-dashboard permission, not the vulnerabilities permissions.
     */
    public ManagerDashboardVulnerabilityDetailDto getVulnerabilityDetail(String id) {
        Vulnerability vulnerability = vulnerabilityRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vulnerability not found with id: " + id));
        return ManagerDashboardVulnerabilityDetailDto.builder()
                .vulnerability(VulnerabilityDto.fromEntity(vulnerability))
                .assessment(assessmentService.getAssessment(vulnerability.getAssessmentId()))
                .build();
    }

    /**
     * CSV of the filtered assessment set (same columns as the standard assessment export).
     */
    public String exportAssessmentsCsv(ManagerDashboardFilters filters, Authentication authentication) {
        List<AssessmentDto> assessments =
                fetchAssessments(filters, Pageable.unpaged(), authentication).getContent();
        return assessmentService.exportToCsv(assessments);
    }

    /**
     * CSV of the filtered cross-assessment vulnerability set.
     */
    public String exportVulnerabilitiesCsv(ManagerDashboardFilters filters, Authentication authentication) {
        List<ManagerDashboardVulnerabilityDto> rows = collectVulnerabilities(filters, authentication);

        StringBuilder csv = new StringBuilder();
        csv.append("ID,Name,Severity,CVSS,Category,Assessment,App ID,Application,Opened,Closed,Status,Tracking ID\n");
        for (ManagerDashboardVulnerabilityDto row : rows) {
            csv.append(escapeCsv(row.getId())).append(",");
            csv.append(escapeCsv(row.getName())).append(",");
            csv.append(escapeCsv(row.getSeverity() != null ? row.getSeverity().name() : "")).append(",");
            csv.append(escapeCsv(row.getCvssScore() != null ? row.getCvssScore().toString() : "")).append(",");
            csv.append(escapeCsv(row.getCategoryName())).append(",");
            csv.append(escapeCsv(row.getAssessmentName())).append(",");
            csv.append(escapeCsv(row.getAppId())).append(",");
            csv.append(escapeCsv(row.getApplicationName())).append(",");
            csv.append(escapeCsv(row.getOpenedAt() != null ? row.getOpenedAt().toString() : "")).append(",");
            csv.append(escapeCsv(row.getClosedAt() != null ? row.getClosedAt().toString() : "")).append(",");
            csv.append(escapeCsv(row.getStatus())).append(",");
            csv.append(escapeCsv(row.getTrackingId())).append("\n");
        }
        return csv.toString();
    }

    // ── internals ────────────────────────────────────────────────────────────

    private Page<AssessmentDto> fetchAssessments(
            ManagerDashboardFilters f, Pageable pageable, Authentication authentication) {
        return assessmentService.searchAssessmentsAdvanced(
                f.getSearch(), f.getApplicationId(), null, null, f.getAssessmentTypeId(),
                f.getAssessorId(), f.getStatus(), null, null,
                f.getStartDateFrom(), f.getStartDateTo(), f.getEndDateFrom(), f.getEndDateTo(),
                null, f.getShowCompleted(), null, null,
                f.getTeamId(), f.getCampaignId(), f.getSeverities(),
                pageable, authentication);
    }

    private List<ManagerDashboardVulnerabilityDto> collectVulnerabilities(
            ManagerDashboardFilters filters, Authentication authentication) {
        // The free-text search matches vulnerability rows (name / assessment / app id)
        // rather than narrowing the assessment set, so searching the vulnerabilities
        // tab doesn't silently drop assessments whose names don't match.
        ManagerDashboardFilters assessmentFilters = ManagerDashboardFilters.builder()
                .applicationId(filters.getApplicationId())
                .assessmentTypeId(filters.getAssessmentTypeId())
                .assessorId(filters.getAssessorId())
                .status(filters.getStatus())
                .teamId(filters.getTeamId())
                .campaignId(filters.getCampaignId())
                .severities(filters.getSeverities())
                .startDateFrom(filters.getStartDateFrom())
                .startDateTo(filters.getStartDateTo())
                .endDateFrom(filters.getEndDateFrom())
                .endDateTo(filters.getEndDateTo())
                .showCompleted(filters.getShowCompleted())
                .build();
        List<AssessmentDto> assessments =
                fetchAssessments(assessmentFilters, Pageable.unpaged(), authentication).getContent();
        List<ManagerDashboardVulnerabilityDto> rows = collectVulnerabilities(assessments, filters);

        if (filters.getSearch() != null && !filters.getSearch().isEmpty()) {
            String needle = filters.getSearch().toLowerCase();
            rows = rows.stream()
                    .filter(r -> containsIgnoreCase(r.getName(), needle)
                            || containsIgnoreCase(r.getAssessmentName(), needle)
                            || containsIgnoreCase(r.getAppId(), needle))
                    .collect(Collectors.toList());
        }
        return rows;
    }

    private boolean containsIgnoreCase(String value, String lowerCaseNeedle) {
        return value != null && value.toLowerCase().contains(lowerCaseNeedle);
    }

    private List<ManagerDashboardVulnerabilityDto> collectVulnerabilities(
            List<AssessmentDto> assessments, ManagerDashboardFilters filters) {
        if (assessments.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, AssessmentDto> assessmentsById = assessments.stream()
                .collect(Collectors.toMap(AssessmentDto::getId, Function.identity()));
        Map<String, String> categoryNamesById = vulnerabilityCategoryRepository.findAllByDeletedAtIsNull().stream()
                .collect(Collectors.toMap(VulnerabilityCategory::getId, VulnerabilityCategory::getName));

        return vulnerabilityRepository
                .findByAssessmentIdInAndDeletedAtIsNull(new ArrayList<>(assessmentsById.keySet())).stream()
                .filter(v -> v.getOpenedAt() != null)
                .filter(v -> filters.getStartDateFrom() == null || !v.getOpenedAt().isBefore(filters.getStartDateFrom()))
                .filter(v -> filters.getStartDateTo() == null || !v.getOpenedAt().isAfter(filters.getStartDateTo()))
                .filter(v -> filters.getSeverities() == null || filters.getSeverities().isEmpty()
                        || filters.getSeverities().contains(v.getSeverity()))
                .sorted(Comparator.comparing(Vulnerability::getOpenedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(v -> toVulnerabilityRow(v, assessmentsById.get(v.getAssessmentId()), categoryNamesById))
                .collect(Collectors.toList());
    }

    private ManagerDashboardVulnerabilityDto toVulnerabilityRow(
            Vulnerability v, AssessmentDto assessment, Map<String, String> categoryNamesById) {
        return ManagerDashboardVulnerabilityDto.builder()
                .id(v.getId())
                .name(v.getName())
                .severity(v.getSeverity())
                .cvssScore(v.getCvssScore())
                .categoryName(v.getVulnerabilityCategoryId() != null
                        ? categoryNamesById.get(v.getVulnerabilityCategoryId())
                        : null)
                .openedAt(v.getOpenedAt())
                .closedAt(v.getClosedAt())
                .status(v.getStatus())
                .trackingId(v.getTrackingId())
                .assessmentId(v.getAssessmentId())
                .assessmentName(assessment != null ? assessment.getName() : null)
                .appId(assessment != null ? assessment.getAppId() : null)
                .applicationName(assessment != null ? assessment.getApplicationName() : null)
                .build();
    }

    private List<String> resolveTeamNames(
            AssessmentDto assessment, Map<String, User> usersById, Map<String, String> teamNamesById) {
        if (assessment.getAssessorIds() == null) {
            return Collections.emptyList();
        }
        return assessment.getAssessorIds().stream()
                .map(usersById::get)
                .filter(u -> u != null && u.getTeamIds() != null)
                .flatMap(u -> u.getTeamIds().stream())
                .distinct()
                .map(teamNamesById::get)
                .filter(name -> name != null)
                .sorted()
                .collect(Collectors.toList());
    }

    private String displayName(User user, String fallbackId) {
        if (user == null) {
            return fallbackId;
        }
        if (user.getFirstName() != null && user.getLastName() != null) {
            return user.getFirstName() + " " + user.getLastName();
        }
        return user.getUsername();
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
