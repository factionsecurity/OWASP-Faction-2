package com.faction.clientportal.service.extension;

import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentChecklist;
import com.faction.clientportal.model.Campaign;
import com.faction.clientportal.model.Extension;
import com.faction.clientportal.model.ExtensionLog;
import com.faction.clientportal.model.Retest;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.AssessmentChecklistRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.CampaignRepository;
import com.faction.clientportal.repository.ExtensionLogRepository;
import com.faction.clientportal.repository.RetestRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityCategoryRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.elements.results.AssessmentManagerResult;
import com.faction.elements.results.InventoryResult;
import com.faction.elements.utils.Log;
import com.faction.extender.ApplicationInventory;
import com.faction.extender.AssessmentManager;
import com.faction.extender.BaseInterface;
import com.faction.extender.ReportManager;
import com.faction.extender.VerificationManager;
import com.faction.extender.VulnerabilityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.faction.clientportal.service.extension.ExtensionRegistry.EventType;
import static com.faction.clientportal.service.extension.ExtensionRegistry.LoadedExtension;

/**
 * The single entry point services use to fire extension hooks.
 *
 * <p>Two execution modes, chosen by what the caller needs back:
 *
 * <ul>
 *   <li><b>Asynchronous, after commit</b> — assessment, vulnerability and
 *       verification events. These typically call out to an issue tracker, so a
 *       slow or unreachable Jira must never hold a user's save open. Firing
 *       <em>after commit</em> rather than inline also guarantees the extension
 *       reads the state that was actually persisted: a Finalize hook that ran
 *       inline could otherwise push pre-finalization data to the remote system,
 *       or push data from a transaction that then rolled back.</li>
 *   <li><b>Synchronous</b> — report generation and application inventory, whose
 *       return values are needed by the caller.</li>
 * </ul>
 *
 * <p>Every invocation is individually guarded. An extension that throws is logged
 * against its own row and skipped; the remaining extensions and the Faction
 * operation that triggered them both carry on.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExtensionEventService {

    private final ExtensionRegistry registry;
    private final ExtensionMapper mapper;
    private final ExtensionLogRepository extensionLogRepository;

    private final AssessmentRepository assessmentRepository;
    private final AssessmentChecklistRepository assessmentChecklistRepository;
    private final AssessmentTypeRepository assessmentTypeRepository;
    private final CampaignRepository campaignRepository;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final com.faction.clientportal.repository.VulnerabilityStageCompletionRepository stageCompletionRepository;
    private final VulnerabilityCategoryRepository vulnerabilityCategoryRepository;
    private final RetestRepository retestRepository;
    private final UserRepository userRepository;

    private final TaskExecutor extensionTaskExecutor;

    // ── Assessment events ────────────────────────────────────────────────────

    /**
     * Fires {@code AssessmentManager.assessmentChange} for every enabled extension.
     *
     * @param operation Create, Update, Delete, Finalize, or one of the peer-review
     *                  operations
     */
    public void assessmentChanged(String assessmentId, AssessmentManager.Operation operation) {
        if (assessmentId == null || !registry.isExtended(EventType.ASMT_MANAGER)) return;

        runAfterCommit(() -> {
            Assessment assessment = assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId).orElse(null);
            if (assessment == null) return;

            List<Vulnerability> vulns =
                    vulnerabilityRepository.findByAssessmentIdAndDeletedAtIsNull(assessmentId);

            dispatchAssessment(buildAssessmentElement(assessment), buildVulnerabilityElements(vulns),
                    operation, assessment, vulns);
        });
    }

    /**
     * Fires {@code Operation.Delete}, capturing the assessment before it is removed.
     *
     * <p>Call this <em>before</em> the soft delete. Deletion hides the row from
     * {@code findByIdAndDeletedAtIsNull}, so the usual after-commit reload would find
     * nothing and the extension would be handed an empty payload — leaving an
     * integration unable to tell which remote records to retire.
     */
    public void assessmentDeleting(String assessmentId) {
        if (assessmentId == null || !registry.isExtended(EventType.ASMT_MANAGER)) return;

        Assessment assessment = assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId).orElse(null);
        if (assessment == null) return;

        com.faction.elements.Assessment element = buildAssessmentElement(assessment);
        List<com.faction.elements.Vulnerability> vulnElements = buildVulnerabilityElements(
                vulnerabilityRepository.findByAssessmentIdAndDeletedAtIsNull(assessmentId));

        // Nothing to write back to — the assessment is on its way out.
        runAfterCommit(() -> dispatchAssessment(
                element, vulnElements, AssessmentManager.Operation.Delete, null, null));
    }

    private void dispatchAssessment(com.faction.elements.Assessment element,
                                    List<com.faction.elements.Vulnerability> vulnElements,
                                    AssessmentManager.Operation operation,
                                    Assessment assessment,
                                    List<Vulnerability> vulns) {

        for (LoadedExtension<AssessmentManager> loaded : registry.<AssessmentManager>get(EventType.ASMT_MANAGER)) {
            invoke(loaded, EventType.ASMT_MANAGER, () -> {
                AssessmentManagerResult result =
                        loaded.getInstance().assessmentChange(element, vulnElements, operation);
                if (result == null || assessment == null) return;

                if (result.getAssessment() != null) {
                    mapper.applyTo(result.getAssessment(), assessment);
                    assessment.setUpdatedAt(LocalDateTime.now());
                    assessmentRepository.save(assessment);
                }
                if (result.getVulnerabilities() != null) {
                    persistVulnerabilityEdits(result.getVulnerabilities(), vulns);
                }
            });
        }
    }

    // ── Vulnerability events ─────────────────────────────────────────────────

    public void vulnerabilityChanged(String assessmentId, String vulnerabilityId,
                                     VulnerabilityManager.Operation operation) {
        if (vulnerabilityId == null || !registry.isExtended(EventType.VULN_MANAGER)) return;

        runAfterCommit(() -> {
            Assessment assessment = assessmentId == null ? null
                    : assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId).orElse(null);
            Vulnerability vuln = vulnerabilityRepository.findById(vulnerabilityId).orElse(null);
            if (vuln == null) return;

            dispatchVulnerability(
                    assessment == null ? null : buildAssessmentElement(assessment),
                    mapper.toElement(vuln, categoryNameOf(vuln), devClosedAt(vuln.getId())), operation, vuln);
        });
    }

    /** The vulnerability's completion date for the default "development" remediation stage —
     *  what the extender API's {@code devClosed} field carries (mirrors the DOCX report variable). */
    private java.time.LocalDateTime devClosedAt(String vulnerabilityId) {
        return stageCompletionRepository.findByVulnerabilityIdAndStageId(vulnerabilityId, "development")
                .map(com.faction.clientportal.model.VulnerabilityStageCompletion::getCompletedAt)
                .orElse(null);
    }

    /**
     * Fires {@code Operation.Delete}, capturing the finding before it is removed —
     * see {@link #assessmentDeleting(String)} for why the capture cannot wait.
     */
    public void vulnerabilityDeleting(String assessmentId, String vulnerabilityId) {
        if (vulnerabilityId == null || !registry.isExtended(EventType.VULN_MANAGER)) return;

        Vulnerability vuln = vulnerabilityRepository.findById(vulnerabilityId).orElse(null);
        if (vuln == null) return;

        Assessment assessment = assessmentId == null ? null
                : assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId).orElse(null);

        com.faction.elements.Assessment assessmentElement =
                assessment == null ? null : buildAssessmentElement(assessment);
        com.faction.elements.Vulnerability vulnElement =
                mapper.toElement(vuln, categoryNameOf(vuln), devClosedAt(vuln.getId()));

        runAfterCommit(() -> dispatchVulnerability(assessmentElement, vulnElement,
                VulnerabilityManager.Operation.Delete, null));
    }

    private void dispatchVulnerability(com.faction.elements.Assessment assessmentElement,
                                       com.faction.elements.Vulnerability vulnElement,
                                       VulnerabilityManager.Operation operation,
                                       Vulnerability vuln) {

        for (LoadedExtension<VulnerabilityManager> loaded
                : registry.<VulnerabilityManager>get(EventType.VULN_MANAGER)) {
            invoke(loaded, EventType.VULN_MANAGER, () -> {
                com.faction.elements.Vulnerability updated =
                        loaded.getInstance().vulnChange(assessmentElement, vulnElement, operation);
                if (updated != null && vuln != null) {
                    mapper.applyTo(updated, vuln);
                    vuln.setUpdatedAt(LocalDateTime.now());
                    vulnerabilityRepository.save(vuln);
                }
            });
        }
    }

    // ── Verification (Retest) events ─────────────────────────────────────────

    public void verificationChanged(String retestId, String changeUserId,
                                    VerificationManager.Operation operation) {
        if (retestId == null || !registry.isExtended(EventType.VER_MANAGER)) return;

        runAfterCommit(() -> {
            Retest retest = retestRepository.findById(retestId).orElse(null);
            if (retest == null) return;

            Vulnerability vuln = retest.getVulnerabilityId() == null ? null
                    : vulnerabilityRepository.findById(retest.getVulnerabilityId()).orElse(null);
            if (vuln == null) return;

            Assessment assessment = retest.getAssessmentId() == null ? null
                    : assessmentRepository.findByIdAndDeletedAtIsNull(retest.getAssessmentId()).orElse(null);

            User changeUser = changeUserId == null ? null : findUser(changeUserId);
            User assignedAssessor = retest.getAssignedAssessorIds() == null
                    || retest.getAssignedAssessorIds().isEmpty()
                        ? null : findUser(retest.getAssignedAssessorIds().get(0));

            com.faction.elements.Vulnerability vulnElement =
                    mapper.toElement(vuln, categoryNameOf(vuln), devClosedAt(vuln.getId()));
            com.faction.elements.Verification verificationElement = mapper.toElement(
                    retest, assessment == null ? null : buildAssessmentElement(assessment), assignedAssessor);
            com.faction.elements.User userElement = mapper.toElement(changeUser);

            for (LoadedExtension<VerificationManager> loaded
                    : registry.<VerificationManager>get(EventType.VER_MANAGER)) {
                invoke(loaded, EventType.VER_MANAGER, () -> {
                    com.faction.elements.Vulnerability updated = loaded.getInstance()
                            .verificationChange(userElement, vulnElement, verificationElement, operation);
                    if (updated != null) {
                        mapper.applyTo(updated, vuln);
                        vuln.setUpdatedAt(LocalDateTime.now());
                        vulnerabilityRepository.save(vuln);
                    }
                });
            }
        });
    }

    // ── Report hook (synchronous) ────────────────────────────────────────────

    /** True when at least one enabled extension implements {@code ReportManager}. */
    public boolean hasReportManagers() {
        return registry.isExtended(EventType.REPORT_MANAGER);
    }

    /**
     * Runs a single piece of report content through every enabled
     * {@code ReportManager}, in order, letting each rewrite it.
     *
     * <p>Content without a {@code ${...}} placeholder is returned untouched
     * without invoking anything — the same short-circuit Faction 1 used, and worth
     * keeping because this runs once per rich-text field per vulnerability.
     *
     * @param element the assessment already mapped for this report run; building it
     *                once and passing it in avoids re-cloning per field
     */
    public String applyReportManagers(com.faction.elements.Assessment element,
                                      List<com.faction.elements.Vulnerability> vulnElements,
                                      String content) {
        if (content == null || !content.contains("${") || !hasReportManagers()) {
            return content;
        }
        String result = content;
        for (LoadedExtension<ReportManager> loaded : registry.<ReportManager>get(EventType.REPORT_MANAGER)) {
            String current = result;
            String updated = invokeForResult(loaded, EventType.REPORT_MANAGER,
                    () -> loaded.getInstance().reportCreate(element, vulnElements, current));
            if (updated != null) {
                result = updated;
            }
        }
        return result;
    }

    // ── Application inventory hook (synchronous) ─────────────────────────────

    /**
     * Asks every enabled {@code ApplicationInventory} extension for applications
     * matching the given id or name, so a CMDB can back Faction's application
     * picker.
     */
    public List<InventoryResult> searchInventory(String applicationId, String applicationName) {
        if (!registry.isExtended(EventType.INVENTORY)) return List.of();

        List<InventoryResult> results = new ArrayList<>();
        for (LoadedExtension<ApplicationInventory> loaded
                : registry.<ApplicationInventory>get(EventType.INVENTORY)) {
            InventoryResult[] found = invokeForResult(loaded, EventType.INVENTORY,
                    () -> loaded.getInstance().search(applicationId, applicationName));
            if (found != null) {
                results.addAll(List.of(found));
            }
        }
        return results;
    }

    // ── Element construction ─────────────────────────────────────────────────

    /** Builds the extender-model assessment, resolving everything an extension can read. */
    public com.faction.elements.Assessment buildAssessmentElement(Assessment assessment) {
        String typeName = assessment.getAssessmentTypeId() == null ? null
                : assessmentTypeRepository.findById(assessment.getAssessmentTypeId())
                        .map(t -> t.getName()).orElse(null);

        String campaignName = assessment.getCampaignId() == null ? null
                : campaignRepository.findById(assessment.getCampaignId())
                        .map(Campaign::getName).orElse(null);

        List<User> assessors = assessment.getAssessorIds() == null ? List.of()
                : assessment.getAssessorIds().stream()
                        .map(this::findUser).filter(Objects::nonNull).toList();

        User engagementContact = findUser(assessment.getEngagementManagerId());
        User remediationContact = findUser(assessment.getRemediationManagerId());

        List<AssessmentChecklist> checklists =
                assessmentChecklistRepository.findByAssessmentId(assessment.getId());

        return mapper.toElement(assessment, typeName, campaignName, assessors,
                engagementContact, remediationContact, checklists);
    }

    /**
     * Builds extender-model vulnerabilities most-severe-first, with category names
     * resolved.
     *
     * <p>Same ordering the report itself uses. An extension that renders a findings
     * table or pushes issues to a tracker should see them in the order a reader will,
     * not in the order the tester happened to type them.
     */
    public List<com.faction.elements.Vulnerability> buildVulnerabilityElements(List<Vulnerability> vulns) {
        Map<String, String> categoryNames = categoryNames(vulns);
        // One batch fetch for the whole report, not a lookup per finding.
        Map<String, java.time.LocalDateTime> devClosedByVuln = new java.util.HashMap<>();
        if (!vulns.isEmpty()) {
            stageCompletionRepository
                    .findByVulnerabilityIdIn(vulns.stream().map(Vulnerability::getId).toList())
                    .stream()
                    .filter(c -> "development".equals(c.getStageId()))
                    .forEach(c -> devClosedByVuln.put(c.getVulnerabilityId(), c.getCompletedAt()));
        }
        return vulns.stream()
                .sorted(Comparator
                        .<Vulnerability>comparingInt(
                                v -> VulnerabilitySeverity.reportRankOf(v.getSeverity()))
                        .thenComparingInt(v -> v.getOrder() == null ? 0 : v.getOrder()))
                .map(v -> mapper.toElement(v,
                        v.getVulnerabilityCategoryId() == null ? null
                                : categoryNames.get(v.getVulnerabilityCategoryId()),
                        devClosedByVuln.get(v.getId())))
                .collect(Collectors.toList());
    }

    private void persistVulnerabilityEdits(List<com.faction.elements.Vulnerability> edited,
                                           List<Vulnerability> vulns) {
        List<Vulnerability> dirty = new ArrayList<>();
        for (com.faction.elements.Vulnerability element : edited) {
            Vulnerability vuln = ExtensionMapper.resolve(vulns, element.getId(), Vulnerability::getId);
            if (vuln == null) continue;
            mapper.applyTo(element, vuln);
            vuln.setUpdatedAt(LocalDateTime.now());
            dirty.add(vuln);
        }
        if (!dirty.isEmpty()) {
            vulnerabilityRepository.saveAll(dirty);
        }
    }

    private Map<String, String> categoryNames(List<Vulnerability> vulns) {
        List<String> ids = vulns.stream()
                .map(Vulnerability::getVulnerabilityCategoryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        return vulnerabilityCategoryRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(c -> c.getId(), c -> c.getName(), (a, b) -> a));
    }

    private String categoryNameOf(Vulnerability vuln) {
        if (vuln.getVulnerabilityCategoryId() == null) return null;
        return vulnerabilityCategoryRepository.findById(vuln.getVulnerabilityCategoryId())
                .map(c -> c.getName()).orElse(null);
    }

    private User findUser(String userId) {
        if (userId == null) return null;
        return userRepository.findById(userId).orElse(null);
    }

    // ── Invocation plumbing ──────────────────────────────────────────────────

    /**
     * Defers work until the surrounding transaction commits, so extensions only
     * ever observe persisted state. With no active transaction the work is
     * submitted straight away.
     */
    private void runAfterCommit(Runnable work) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    extensionTaskExecutor.execute(guarded(work));
                }
            });
        } else {
            extensionTaskExecutor.execute(guarded(work));
        }
    }

    private Runnable guarded(Runnable work) {
        return () -> {
            try {
                work.run();
            } catch (Throwable t) {
                log.error("Extension event dispatch failed", t);
            }
        };
    }

    /** Runs one extension's hook, draining its logs and containing any failure. */
    private void invoke(LoadedExtension<?> loaded, EventType eventType, Runnable work) {
        try {
            work.run();
        } catch (Throwable t) {
            log.error("Extension '{}' failed handling {}", loaded.getExtensionName(), eventType, t);
            recordFailure(loaded, eventType, t);
        } finally {
            drainLogs(loaded, eventType);
        }
    }

    private <T> T invokeForResult(LoadedExtension<?> loaded, EventType eventType,
                                  java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (Throwable t) {
            log.error("Extension '{}' failed handling {}", loaded.getExtensionName(), eventType, t);
            recordFailure(loaded, eventType, t);
            return null;
        } finally {
            drainLogs(loaded, eventType);
        }
    }

    /**
     * Moves whatever the extension logged into {@code extension_log}.
     *
     * <p>Extensions run on a background thread, so anything they print to stdout is
     * effectively lost to the operator who installed them. Persisting the logs is
     * what makes a misconfigured integration diagnosable from the App Store page.
     */
    private void drainLogs(LoadedExtension<?> loaded, EventType eventType) {
        try {
            BaseInterface instance = loaded.getInstance();
            List<Log> logs = instance.getLogs();
            if (logs == null || logs.isEmpty()) return;

            List<ExtensionLog> rows = logs.stream()
                    .map(entry -> ExtensionLog.builder()
                            .extensionId(loaded.getExtensionId())
                            .level(entry.getLevel() == null ? "INFO" : entry.getLevel().name())
                            .eventType(eventType.name())
                            .message(entry.getMessage())
                            .stackTrace(entry.getStackTrace())
                            .timestamp(entry.getTimeStamp() == null ? LocalDateTime.now()
                                    : LocalDateTime.ofInstant(entry.getTimeStamp().toInstant(),
                                                              java.time.ZoneId.systemDefault()))
                            .build())
                    .toList();
            extensionLogRepository.saveAll(rows);
            logs.clear();
        } catch (Throwable t) {
            log.debug("Could not drain logs for extension '{}': {}",
                    loaded.getExtensionName(), t.getMessage());
        }
    }

    private void recordFailure(LoadedExtension<?> loaded, EventType eventType, Throwable t) {
        try {
            java.io.StringWriter trace = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(trace));
            extensionLogRepository.save(ExtensionLog.builder()
                    .extensionId(loaded.getExtensionId())
                    .level("ERROR")
                    .eventType(eventType.name())
                    .message(t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : ": " + t.getMessage()))
                    .stackTrace(trace.toString())
                    .timestamp(LocalDateTime.now())
                    .build());
        } catch (Throwable ignored) {
            // Logging the failure must never itself fail the caller.
        }
    }
}
