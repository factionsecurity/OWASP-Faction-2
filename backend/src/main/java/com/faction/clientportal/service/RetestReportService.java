package com.faction.clientportal.service;

import com.faction.clientportal.dto.RetestReportReadyDto;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.Retest;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.ReportTemplateRepository;
import com.faction.clientportal.repository.RetestRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Retest reports: which completed retests a report covers, which assessments have new retest
 * results since their last report, and the evidence lock a generated report leaves behind.
 */
@Service
@RequiredArgsConstructor
public class RetestReportService {

    /** The retest outcomes a retest report covers. */
    public static final Set<String> COMPLETED_STATUSES = Set.of("PASSED", "FAILED");

    private static final Comparator<LocalDateTime> NULLS_OLDEST =
            Comparator.nullsFirst(Comparator.naturalOrder());

    private final RetestRepository retestRepository;
    private final AssessmentRepository assessmentRepository;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final ApplicationRepository applicationRepository;
    private final ReportTemplateRepository reportTemplateRepository;
    private final UserRepository userRepository;
    private final RetestService retestService;

    /**
     * Vulnerability id to its latest PASSED/FAILED retest by {@code closedDate}. Findings that were
     * never retested to a result, and deleted findings, are absent.
     */
    public Map<String, Retest> latestCompletedByVulnerability(String assessmentId) {
        return completedOnLiveVulnerabilities(assessmentId).stream()
                .collect(Collectors.toMap(Retest::getVulnerabilityId, r -> r,
                        (a, b) -> NULLS_OLDEST.compare(a.getClosedDate(), b.getClosedDate()) >= 0 ? a : b));
    }

    /** Whether the assessment's report template currently carries a retest template file. */
    public boolean hasRetestTemplate(Assessment assessment) {
        return ReportTemplateResolution.forReport(reportTemplateRepository, assessment)
                .map(t -> t.getRetestTemplateFileId() != null)
                .orElse(false);
    }

    /**
     * For each assessment the caller may see, how many of its findings have never been retested
     * to a result: live (not deleted) vulnerabilities with no PASSED or FAILED retest. A finding
     * whose only retests were cancelled counts as untested. Assessments outside the caller's
     * scope, or deleted, are left out of the map.
     */
    public Map<String, Integer> untestedCounts(Collection<String> assessmentIds, Authentication authentication) {
        java.util.function.Predicate<String> visible = retestService.assessmentVisibility(authentication);
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String assessmentId : new java.util.LinkedHashSet<>(assessmentIds)) {
            if (assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId).isEmpty()
                    || !visible.test(assessmentId)) {
                continue;
            }
            Set<String> tested = retestRepository
                    .findByAssessmentIdAndStatusInAndDeletedAtIsNull(assessmentId, COMPLETED_STATUSES)
                    .stream().map(Retest::getVulnerabilityId).collect(Collectors.toSet());
            int untested = (int) vulnerabilityRepository.findByAssessmentIdAndDeletedAtIsNull(assessmentId)
                    .stream().filter(v -> !tested.contains(v.getId())).count();
            counts.put(assessmentId, untested);
        }
        return counts;
    }

    /**
     * Whether the caller is assigned to, or completed, a live retest on the assessment — the
     * people doing the retest work, who may generate its report without edit scope on the
     * assessment itself.
     */
    public boolean isRetestParticipant(String assessmentId, Authentication authentication) {
        if (authentication == null) return false;
        String username = authentication.getName();
        String userId = userRepository.findByUsername(username).map(u -> u.getId()).orElse(null);
        return retestRepository.findByAssessmentIdAndDeletedAtIsNull(assessmentId).stream().anyMatch(r ->
                (username != null && username.equals(r.getCompletedBy()))
                        || (userId != null && r.getAssignedAssessorIds() != null
                                && r.getAssignedAssessorIds().contains(userId)));
    }

    /**
     * Records a retest report: stamps the assessment with the time generation started and locks
     * the evidence of the retests it included. A retest already locked keeps its first lock time.
     */
    @Transactional
    public void recordGeneration(String assessmentId, LocalDateTime startedAt, Collection<String> retestIds) {
        assessmentRepository.findById(assessmentId).ifPresent(a -> {
            a.setRetestReportGeneratedAt(startedAt);
            assessmentRepository.save(a);
        });
        LocalDateTime now = LocalDateTime.now();
        List<Retest> toLock = retestRepository.findAllById(retestIds).stream()
                .filter(r -> r.getEvidenceLockedAt() == null)
                .toList();
        toLock.forEach(r -> r.setEvidenceLockedAt(now));
        retestRepository.saveAll(toLock);
    }

    /**
     * The completed retests that are new since the assessment's last retest report: closed, or
     * with evidence edited, strictly after it. With no report yet, every completed retest counts.
     */
    static List<Retest> isReady(Assessment assessment, List<Retest> completed) {
        LocalDateTime since = assessment.getRetestReportGeneratedAt();
        return completed.stream()
                .filter(r -> since == null || after(r.getClosedDate(), since) || after(r.getEvidenceUpdatedAt(), since))
                .toList();
    }

    private static boolean after(LocalDateTime value, LocalDateTime since) {
        return value != null && value.isAfter(since);
    }

    /**
     * Assessments with retests finished since their last retest report, where at least one of
     * those new retests is assigned to, or was completed by, the user — newest first, limited to
     * what the caller may see. The counts cover every new retest on the assessment, not only the
     * user's own: the user filter decides which rows appear, not what they count.
     */
    public List<RetestReportReadyDto> readyForReport(String username, Authentication authentication) {
        String userId = userRepository.findByUsername(username).map(u -> u.getId()).orElse(username);

        Map<String, Retest> mine = new LinkedHashMap<>();
        retestRepository.findByAssignedAssessorIdsContainingAndDeletedAtIsNull(userId).stream()
                .filter(r -> r.getStatus() != null && COMPLETED_STATUSES.contains(r.getStatus()))
                .forEach(r -> mine.putIfAbsent(r.getId(), r));
        retestRepository.findByCompletedByAndStatusInAndDeletedAtIsNull(username, COMPLETED_STATUSES)
                .forEach(r -> mine.putIfAbsent(r.getId(), r));

        // The user's in-scope retests only pick the candidate assessments.
        Set<String> candidates = retestService.filterToScope(new ArrayList<>(mine.values()), authentication)
                .stream().map(Retest::getAssessmentId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        List<RetestReportReadyDto> rows = new ArrayList<>();
        for (String assessmentId : candidates) {
            assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId).ifPresent(a -> {
                List<Retest> counted = isReady(a, completedOnLiveVulnerabilities(assessmentId));
                boolean includesMine = counted.stream().anyMatch(r ->
                        username.equals(r.getCompletedBy())
                                || (r.getAssignedAssessorIds() != null && r.getAssignedAssessorIds().contains(userId)));
                if (!includesMine) return;
                rows.add(RetestReportReadyDto.builder()
                        .assessmentId(a.getId())
                        .assessmentName(a.getName())
                        .applicationId(a.getApplicationId())
                        .applicationName(a.getApplicationId() == null ? null
                                : applicationRepository.findById(a.getApplicationId())
                                        .map(Application::getName).orElse(null))
                        .passedCount((int) counted.stream().filter(r -> "PASSED".equals(r.getStatus())).count())
                        .failedCount((int) counted.stream().filter(r -> "FAILED".equals(r.getStatus())).count())
                        .lastCompletedAt(counted.stream().map(Retest::getClosedDate)
                                .max(NULLS_OLDEST).orElse(null))
                        .retestReportGeneratedAt(a.getRetestReportGeneratedAt())
                        .hasRetestTemplate(hasRetestTemplate(a))
                        .build());
            });
        }
        rows.sort(Comparator.comparing(RetestReportReadyDto::getLastCompletedAt, NULLS_OLDEST).reversed());
        return rows;
    }

    /** An assessment's PASSED/FAILED retests, excluding those on deleted vulnerabilities. */
    private List<Retest> completedOnLiveVulnerabilities(String assessmentId) {
        Set<String> liveVulnIds = vulnerabilityRepository.findByAssessmentIdAndDeletedAtIsNull(assessmentId)
                .stream().map(Vulnerability::getId).collect(Collectors.toSet());
        return retestRepository.findByAssessmentIdAndStatusInAndDeletedAtIsNull(assessmentId, COMPLETED_STATUSES)
                .stream().filter(r -> liveVulnIds.contains(r.getVulnerabilityId())).toList();
    }
}
