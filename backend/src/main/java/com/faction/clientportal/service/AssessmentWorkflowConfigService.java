package com.faction.clientportal.service;

import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.RemediationStage;
import com.faction.clientportal.model.VulnerabilitySla;
import com.faction.clientportal.repository.AssessmentWorkflowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default Workflow's settings, and {@code GET|PUT /api/v1/config/assessment-workflow}'s alias for
 * them. Single-assessment code reads an assessment's own workflow via {@link WorkflowCatalogService}
 * instead; this remains the source for readers that span workflows.
 */
@Service
@RequiredArgsConstructor
public class AssessmentWorkflowConfigService {

    private final AssessmentWorkflowRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final WorkflowSettingsGuard settingsGuard;

    /**
     * Default Workflow, created with the default settings when it does not exist yet.
     *
     * <p>Two callers racing to create it both find it missing; the loser's insert hits the
     * primary-key/name conflict, so it re-reads the winner's row instead of failing outright
     * (which would otherwise fail {@code ApplicationRunner} at startup).
     */
    public AssessmentWorkflow ensureDefaultWorkflow() {
        return repository.findById(AssessmentWorkflow.DEFAULT_ID)
                .orElseGet(this::createDefaultWorkflow);
    }

    private AssessmentWorkflow createDefaultWorkflow() {
        try {
            return repository.save(newDefaultWorkflow());
        } catch (DataIntegrityViolationException e) {
            return repository.findById(AssessmentWorkflow.DEFAULT_ID).orElseThrow(() -> e);
        }
    }

    /** Returns Default Workflow, creating it with defaults on first access. */
    public AssessmentWorkflow getConfig() {
        return ensureDefaultWorkflow();
    }

    /** What {@link #saveSettings} saved, and whether the save changed the SLAs (so due dates must be recalculated). */
    public record SettingsSave(AssessmentWorkflow workflow, boolean slasChanged) {
    }

    /**
     * Saves the submitted settings onto Default Workflow exactly as submitted, after refusing the save
     * when it removes a status, vulnerability status or stage still in use. What identifies the
     * workflow — id, name, default flag, archived flag, creation time — is never taken from the
     * submission.
     */
    public AssessmentWorkflow updateConfig(AssessmentWorkflow submitted) {
        // With no row yet, getConfig() would have seeded the defaults, so those are what changed from.
        AssessmentWorkflow workflow = repository.findById(AssessmentWorkflow.DEFAULT_ID)
                .orElseGet(AssessmentWorkflowConfigService::newDefaultWorkflow);
        settingsGuard.check(AssessmentWorkflow.DEFAULT_ID, WorkflowSettingsChange.untracked(workflow, submitted));

        SettingsSave result = saveSettings(workflow, submitted);
        if (result.slasChanged()) {
            eventPublisher.publishEvent(new SlaConfigChangedEvent(AssessmentWorkflow.DEFAULT_ID));
        }
        return result.workflow();
    }

    /**
     * Copies the settings fields of {@code submitted} onto {@code workflow} and saves it. Neither guards
     * nor announces: callers check {@link WorkflowSettingsGuard} first and publish
     * {@link SlaConfigChangedEvent} once the save is committed.
     */
    public SettingsSave saveSettings(AssessmentWorkflow workflow, AssessmentWorkflow submitted) {
        List<VulnerabilitySla> previousSlas = workflow.getVulnerabilitySlas();

        workflow.setStatuses(submitted.getStatuses());
        workflow.setNewAssessmentStatus(submitted.getNewAssessmentStatus());
        workflow.setInProgressStatus(submitted.getInProgressStatus());
        workflow.setCompletedStatus(submitted.getCompletedStatus());
        workflow.setStatusColors(submitted.getStatusColors());
        workflow.setVulnerabilitySlas(submitted.getVulnerabilitySlas());
        workflow.setVulnerabilityStatuses(submitted.getVulnerabilityStatuses());
        workflow.setRemediationStages(normalizeStages(submitted.getRemediationStages()));
        workflow.setAllowSelfPeerReview(submitted.isAllowSelfPeerReview());
        workflow.setUpdatedAt(LocalDateTime.now());

        AssessmentWorkflow saved = repository.save(workflow);
        boolean slasChanged = !normalizedSlas(previousSlas).equals(normalizedSlas(saved.getVulnerabilitySlas()));
        return new SettingsSave(saved, slasChanged);
    }

    private static AssessmentWorkflow newDefaultWorkflow() {
        LocalDateTime now = LocalDateTime.now();
        return AssessmentWorkflow.defaultWorkflowBuilder().createdAt(now).updatedAt(now).build();
    }

    /**
     * The SLAs as SlaService reads them — severity trimmed and upper-cased, with its day counts — as
     * an order-insensitive set, so reordering rows or retyping a severity's case is not a change.
     */
    static Set<String> normalizedSlas(List<VulnerabilitySla> slas) {
        if (slas == null) {
            return Set.of();
        }
        return slas.stream()
                .filter(s -> s != null && s.getSeverity() != null && !s.getSeverity().isBlank())
                .map(s -> s.getSeverity().trim().toUpperCase(Locale.ROOT)
                        + ":" + s.getPastDueDays() + ":" + s.getWarningDays())
                .collect(Collectors.toSet());
    }

    /**
     * Default Workflow's remediation stages, kept for callers that span workflows. Never empty:
     * there must always be a terminal (last) stage for closing a vulnerability, so a null/empty
     * list falls back to the defaults. Single-assessment code uses {@link AssessmentWorkflows}
     * with the assessment's own workflow.
     */
    public List<RemediationStage> remediationStages() {
        return AssessmentWorkflows.stages(getConfig());
    }

    /**
     * Stage ids are assigned server-side and are permanent — completions are keyed by them, so a
     * rename must never change the id. Blank-named stages are dropped; an empty submission falls
     * back to the defaults rather than leaving the config without a terminal stage.
     */
    private static List<RemediationStage> normalizeStages(List<RemediationStage> stages) {
        if (stages == null) {
            return AssessmentWorkflow.defaultRemediationStages();
        }
        List<RemediationStage> normalized = new ArrayList<>();
        for (RemediationStage stage : stages) {
            if (stage == null || stage.getName() == null || stage.getName().isBlank()) continue;
            String id = stage.getId() == null || stage.getId().isBlank()
                    ? UUID.randomUUID().toString() : stage.getId();
            normalized.add(new RemediationStage(id, stage.getName().trim()));
        }
        return normalized.isEmpty() ? AssessmentWorkflow.defaultRemediationStages() : normalized;
    }

    /**
     * Whether the given status is Default Workflow's configured completed status, kept for callers
     * that span workflows. Single-assessment code uses {@link AssessmentWorkflows} with the
     * assessment's own workflow.
     */
    public boolean isCompletedStatus(String status) {
        if (status == null) return false;
        return AssessmentWorkflows.isCompleted(getConfig(), status);
    }
}
