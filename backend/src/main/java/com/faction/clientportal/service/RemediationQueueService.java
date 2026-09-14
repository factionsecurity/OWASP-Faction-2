package com.faction.clientportal.service;

import com.faction.clientportal.dto.RemediationQueueSummaryDto;
import com.faction.clientportal.dto.RemediationRowDto;
import com.faction.clientportal.model.AssessmentWorkflowConfig.VulnerabilitySla;
import com.faction.clientportal.model.Retest;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.RemediationQueueCriteria;
import com.faction.clientportal.repository.RetestRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.repository.VulnerabilityRepositoryCustom.RemediationDueRow;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The remediation queue: open tracked vulnerabilities at or past their SLA warning threshold, plus
 * retests that are requested, scheduled, or in progress. Exposes both the badge count
 * ({@link #queueCount}) and the interleaved, paginated page the Remediation pane renders
 * ({@link #list}) — both computed with database aggregates, no per-assessment fan-out.
 *
 * <p>The list is row-level scoped via {@link VulnerabilityScopeResolver} (the {@code @RequiresPermission}
 * gate only checks the caller holds some vulnerability read permission). {@code queueCount} stays a global
 * badge — a pre-existing inconsistency left as-is.
 */
@Service
@RequiredArgsConstructor
public class RemediationQueueService {


    private final VulnerabilityRepository vulnerabilityRepository;
    private final RetestRepository retestRepository;
    private final AssessmentWorkflowConfigService workflowConfigService;
    private final VulnerabilityScopeResolver scopeResolver;

    /**
     * The nav badge number for this caller: the rows of their queue, scoped exactly as {@link #list}
     * scopes them. Counted from the queue's own query, so the badge, the page's Total and the table
     * can never disagree.
     */
    public long queueCount(Authentication authentication) {
        return summary(null, null, null, null, null, null, null, authentication).total();
    }

    /** Unscoped count of the whole queue, for internal callers with no user. */
    public long queueCount() {
        return summarize(RemediationQueueCriteria.builder().build()).total();
    }

    /**
     * The queue broken into its badge buckets, under the same scope and filters as {@link #list} —
     * minus the bucket selection itself, so choosing a badge never shrinks its siblings' counts, and
     * minus completed retests, which are no bucket's work.
     */
    public RemediationQueueSummaryDto summary(String search,
                                              String severity,
                                              String organizationId,
                                              String applicationId,
                                              String assessmentId,
                                              List<String> statuses,
                                              String type,
                                              Authentication authentication) {
        var es = scopeResolver.effectiveScope(organizationId, applicationId, authentication);
        if (es.denied()) {
            return RemediationQueueSummaryDto.empty();
        }
        return summarize(RemediationQueueCriteria.builder()
                .search(search)
                .severityOrdinals(severityOrdinals(severity))
                .organizationIds(es.orgIds())
                .scopeAppIds(es.scopeAppIds())
                .applicationIds(es.appIds())
                .teamIds(es.teamIds())
                .assessorId(es.assessorId())
                .assessmentId(assessmentId)
                .statuses(statuses)
                .rowType(rowType(type))
                .includeCompletedRetests(false)
                .build());
    }

    private RemediationQueueSummaryDto summarize(RemediationQueueCriteria criteria) {
        var slas = workflowConfigService.getConfig().getVulnerabilitySlas();
        Integer[] warn = slas == null ? new Integer[0] : warnDaysByOrdinal(slas);
        Integer[] due = slas == null ? new Integer[0] : dueDaysByOrdinal(slas);
        java.util.Map<String, Long> counts = vulnerabilityRepository.countRemediationBuckets(warn, due, criteria);
        long pastDue = counts.getOrDefault("PAST_DUE", 0L);
        long dueSoon = counts.getOrDefault("DUE_SOON", 0L);
        long requested = counts.getOrDefault("RETEST_REQUESTED", 0L);
        long scheduled = counts.getOrDefault("RETEST_SCHEDULED", 0L);
        long inProgress = counts.getOrDefault("RETEST_IN_PROGRESS", 0L);
        return new RemediationQueueSummaryDto(pastDue + dueSoon + requested + scheduled + inProgress,
                pastDue, dueSoon, requested, scheduled, inProgress);
    }

    /**
     * One page of the interleaved remediation queue, scoped to what the caller may read, with joined
     * application / organization names and each vuln row's last PASSED/FAILED retest result. Urgent
     * first, then warning, then not-yet-due; within a tier by due date. Replaces the pane's old
     * client-side fan-out over every assessment's vulnerabilities.
     *
     * <p>The optional filters mirror the vulnerabilities list's header (organization, severity,
     * application, assessment, statuses), plus {@code type} for the queue's own two row kinds; the
     * org/application ones are intersected with the caller's scope by
     * {@link VulnerabilityScopeResolver#effectiveScope}, so a filter can never widen it.
     *
     * <p>{@code includeCompletedRetests} additionally shows retests that have been verified
     * (PASSED / FAILED) alongside the outstanding ones, for looking back over what was checked.
     * It never widens the vulnerability half: a closed finding is not a queue row.
     */
    public Page<RemediationRowDto> list(String search,
                                        String severity,
                                        String organizationId,
                                        String applicationId,
                                        String assessmentId,
                                        List<String> statuses,
                                        String type,
                                        List<String> buckets,
                                        boolean includeCompletedRetests,
                                        Pageable pageable,
                                        Authentication authentication) {
        var es = scopeResolver.effectiveScope(organizationId, applicationId, authentication);
        if (es.denied()) {
            return Page.empty(pageable);
        }

        var slas = workflowConfigService.getConfig().getVulnerabilitySlas();
        Integer[] warn = slas == null ? new Integer[0] : warnDaysByOrdinal(slas);
        Integer[] due = slas == null ? new Integer[0] : dueDaysByOrdinal(slas);

        var criteria = RemediationQueueCriteria.builder()
                .search(search)
                .severityOrdinals(severityOrdinals(severity))
                .organizationIds(es.orgIds())
                .scopeAppIds(es.scopeAppIds())
                .applicationIds(es.appIds())
                .teamIds(es.teamIds())
                .assessorId(es.assessorId())
                .assessmentId(assessmentId)
                .statuses(statuses)
                .rowType(rowType(type))
                .buckets(buckets)
                .includeCompletedRetests(includeCompletedRetests)
                .build();

        Page<RemediationDueRow> page = vulnerabilityRepository.listRemediationDue(warn, due, criteria, pageable);

        return new PageImpl<>(toDtos(page.getContent()), pageable, page.getTotalElements());
    }

    /**
     * CSV of the whole filtered queue — the same rows, order, and columns the table shows, without
     * its pagination. Deliberately unpaged: a capped export would silently hand back a truncated
     * file that still looks complete.
     *
     * <p>Retest rows carry three columns the table has no room for — completed date, result, and who
     * verified it — so the export answers "what was retested, and when did it close?" over a period.
     * Reaching them needs {@code includeCompletedRetests}; without it the queue is a worklist of
     * outstanding items only and every completion column comes back blank.
     */
    public String exportCsv(String search,
                            String severity,
                            String organizationId,
                            String applicationId,
                            String assessmentId,
                            List<String> statuses,
                            String type,
                            List<String> buckets,
                            boolean includeCompletedRetests,
                            Sort sort,
                            Authentication authentication) {
        List<RemediationRowDto> rows = list(search, severity, organizationId, applicationId, assessmentId,
                statuses, type, buckets, includeCompletedRetests,
                Pageable.unpaged(sort == null ? Sort.unsorted() : sort), authentication).getContent();

        // The queue's union query doesn't carry a retest's completion fields (the table has no
        // column for them), so fetch them for this result set in one batch — not per row.
        Map<String, Retest> retestsById = retestsFor(rows);

        StringBuilder csv = new StringBuilder();
        csv.append("Type,Vulnerability,Severity,Status,Application,Organization,Due Date,")
           .append("Scheduled Start,Scheduled End,Retest Status,Last Retest,Last Retest Date,")
           .append("Completed Date,Result,Completed By\n");

        for (RemediationRowDto row : rows) {
            Retest retest = "RETEST".equals(row.getType()) ? retestsById.get(row.getId()) : null;
            csv.append(escapeCsv(row.getType())).append(",");
            csv.append(escapeCsv(row.getVulnerabilityName())).append(",");
            csv.append(escapeCsv(row.getSeverity() != null ? row.getSeverity().name() : "")).append(",");
            csv.append(escapeCsv(row.getVulnerabilityStatus())).append(",");
            csv.append(escapeCsv(row.getApplicationName())).append(",");
            csv.append(escapeCsv(row.getOrganizationName())).append(",");
            csv.append(escapeCsv(row.getDueDate() != null ? row.getDueDate().toString() : "")).append(",");
            csv.append(escapeCsv(formatTimestamp(row.getStartDate()))).append(",");
            csv.append(escapeCsv(formatTimestamp(row.getEndDate()))).append(",");
            csv.append(escapeCsv(row.getRetestStatus())).append(",");
            csv.append(escapeCsv(row.getLastRetestStatus())).append(",");
            csv.append(escapeCsv(formatTimestamp(row.getLastRetestDate()))).append(",");
            csv.append(escapeCsv(retest != null ? formatTimestamp(retest.getClosedDate()) : "")).append(",");
            csv.append(escapeCsv(retest != null ? retest.getResult() : "")).append(",");
            csv.append(escapeCsv(retest != null ? retest.getCompletedBy() : "")).append("\n");
        }
        return csv.toString();
    }

    /** The retests behind this result set's retest rows, keyed by id. Empty when there are none. */
    private Map<String, Retest> retestsFor(List<RemediationRowDto> rows) {
        List<String> ids = rows.stream()
                .filter(r -> "RETEST".equals(r.getType()))
                .map(RemediationRowDto::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return retestRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Retest::getId, Function.identity()));
    }

    private static String formatTimestamp(LocalDateTime value) {
        return value == null ? "" : value.toString();
    }

    /** RFC-4180 quoting: quote whenever the value carries a comma, quote, or newline. */
    private static String escapeCsv(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** The two row kinds the queue holds — the accepted values of the {@code type} filter. */
    private static final Set<String> ROW_TYPES = Set.of("VULNERABILITY", "RETEST");

    /** Normalized row type for the query; null (no filter) when absent or not one of {@link #ROW_TYPES}. */
    private static String rowType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String normalized = type.trim().toUpperCase();
        return ROW_TYPES.contains(normalized) ? normalized : null;
    }

    /** Severity name (e.g. "HIGH") → the single-element ordinal set the query filters on; null (no
     *  filter) when absent or not a severity. The queue's filter is single-select, but the criteria
     *  it shares with the vulnerabilities list takes a set. */
    private static Collection<Integer> severityOrdinals(String severity) {
        if (severity == null || severity.isBlank()) {
            return null;
        }
        try {
            return Set.of(VulnerabilitySeverity.valueOf(severity.trim().toUpperCase()).ordinal());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<RemediationRowDto> toDtos(List<RemediationDueRow> rows) {
        return rows.stream().map(r -> {
            boolean isVuln = "VULNERABILITY".equals(r.rowType());
            return RemediationRowDto.builder()
                    .key((isVuln ? "vuln-" : "retest-") + r.rowId())
                    .id(r.rowId())
                    .type(r.rowType())
                    .vulnerabilityId(r.vulnerabilityId())
                    .vulnerabilityName(r.name())
                    .severity(severityFromOrdinal(r.severity()))
                    .assessmentId(r.assessmentId())
                    .applicationId(r.applicationId())
                    .applicationName(r.applicationName())
                    .organizationId(r.organizationId())
                    .organizationName(r.organizationName())
                    .dueDate(r.dueDate())
                    .startDate(r.startDate())
                    .endDate(r.endDate())
                    .urgent(r.urgent())
                    .warning(r.warning())
                    // The underlying vulnerability's status, on both row types — the queue's Status
                    // column always shows the vuln's status, not the retest's.
                    .vulnerabilityStatus(r.vulnerabilityStatus())
                    .retestStatus(isVuln ? null : r.retestStatus())
                    .lastRetestStatus(isVuln ? r.lastRetestStatus() : null)
                    .lastRetestDate(isVuln ? r.lastRetestDate() : null)
                    .build();
        }).toList();
    }

    private static VulnerabilitySeverity severityFromOrdinal(Integer ordinal) {
        if (ordinal == null || ordinal < 0 || ordinal >= VulnerabilitySeverity.values().length) {
            return null;
        }
        return VulnerabilitySeverity.values()[ordinal];
    }

    /**
     * Per-severity warning threshold ({@code pastDueDays - warningDays}) indexed by
     * severity ordinal; null where the severity has no SLA (untracked, so excluded).
     */
    private static Integer[] warnDaysByOrdinal(List<VulnerabilitySla> slas) {
        var warn = new Integer[VulnerabilitySeverity.values().length];
        for (var sla : slas) {
            try {
                // Normalize case so a config severity like "High"/"high" still matches the enum
                // (mirrors VulnerabilityPastDueJob; a bare valueOf would silently drop it).
                warn[VulnerabilitySeverity.valueOf(sla.getSeverity().trim().toUpperCase()).ordinal()] =
                        sla.getPastDueDays() - sla.getWarningDays();
            } catch (IllegalArgumentException | NullPointerException ignored) {
                // Unknown/blank severity name in config — skip it.
            }
        }
        return warn;
    }

    /** Per-severity {@code pastDueDays} (the SLA deadline) indexed by severity ordinal; null where untracked. */
    private static Integer[] dueDaysByOrdinal(List<VulnerabilitySla> slas) {
        var due = new Integer[VulnerabilitySeverity.values().length];
        for (var sla : slas) {
            try {
                due[VulnerabilitySeverity.valueOf(sla.getSeverity().trim().toUpperCase()).ordinal()] =
                        sla.getPastDueDays();
            } catch (IllegalArgumentException | NullPointerException ignored) {
                // Unknown/blank severity name in config — skip it.
            }
        }
        return due;
    }
}
