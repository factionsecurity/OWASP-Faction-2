package com.faction.clientportal.service;

import com.faction.clientportal.dto.workflow.WorkflowMovePreviewDto;
import com.faction.clientportal.edition.EditionPolicy;
import com.faction.clientportal.edition.Feature;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.exception.WorkflowConflictException;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.RemediationStage;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilityStageCompletion;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.repository.VulnerabilityStageCompletionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Moves an assessment onto another workflow, mapping its status, its findings (status and stored due
 * dates) and its stage completions, or previews the move without writing anything. A real move loads,
 * maps and writes everything inside one transaction — the entities involved carry no {@code @Version},
 * so reading them ahead of a separate write transaction would let a concurrent edit be silently
 * overwritten. A dry run needs no transaction and never mutates a loaded entity; findings are saved in
 * batches when applying.
 */
@Service
@RequiredArgsConstructor
public class AssessmentWorkflowMoveService {

    static final int SAVE_BATCH = 500;

    private final AssessmentRepository assessmentRepository;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final VulnerabilityStageCompletionRepository stageCompletionRepository;
    private final WorkflowCatalogService workflowCatalogService;
    private final WorkflowMappingService mappingService;
    private final EditionPolicy editionPolicy;
    private final TransactionTemplate transactionTemplate;

    /**
     * @throws ResourceNotFoundException the assessment does not exist or is deleted
     * @throws IllegalArgumentException  the target is missing, unknown, or the workflow the assessment is on
     * @throws WorkflowConflictException the target is archived
     */
    public WorkflowMovePreviewDto move(String assessmentId, String targetWorkflowId, boolean dryRun) {
        return move(assessmentId, targetWorkflowId, dryRun, workflowCatalogService.load());
    }

    /**
     * As {@link #move(String, String, boolean)}, taking a catalog the caller already loaded — for a caller
     * (such as {@code AssessmentService#updateAssessment}) that both validates a move with
     * {@link #checkMove} and applies it within the one request, so the catalog is loaded once for both.
     */
    public WorkflowMovePreviewDto move(String assessmentId, String targetWorkflowId, boolean dryRun, WorkflowCatalog catalog) {
        if (dryRun) {
            return performMove(assessmentId, targetWorkflowId, true, catalog);
        }
        return transactionTemplate.execute(status -> performMove(assessmentId, targetWorkflowId, false, catalog));
    }

    /**
     * Validates a move without loading or mapping any finding or stage completion: unknown/blank/current
     * target (400), archived target (409), and the edition gate for any target other than Default
     * Workflow. Returns the resolved target workflow. Cheap enough to call purely to check whether a move
     * would be refused, ahead of doing anything else the refusal should prevent.
     *
     * @throws IllegalArgumentException  the target is missing, unknown, or the workflow the assessment is on
     * @throws WorkflowConflictException the target is archived
     */
    public AssessmentWorkflow checkMove(Assessment assessment, String targetWorkflowId, WorkflowCatalog catalog) {
        if (targetWorkflowId == null || targetWorkflowId.isBlank()) {
            throw new IllegalArgumentException("A target workflow is required");
        }
        AssessmentWorkflow target = catalog.workflows(true).stream()
                .filter(workflow -> workflow.getId().equals(targetWorkflowId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown workflow: " + targetWorkflowId));
        if (target.getId().equals(assessment.getWorkflowId())) {
            throw new IllegalArgumentException("The assessment is already on " + target.getName());
        }
        if (target.isArchived()) {
            throw new WorkflowConflictException(new WorkflowConflictException.Violation(
                    WorkflowConflictException.TARGET_ARCHIVED, target.getName(), 0));
        }
        if (!AssessmentWorkflow.DEFAULT_ID.equals(target.getId())) {
            editionPolicy.require(Feature.CUSTOM_WORKFLOWS);
        }
        return target;
    }

    /**
     * Loads the assessment, maps its status/findings/stage completions, and — only when applying — writes
     * everything. Called directly for a dry run (no transaction) and through {@code transactionTemplate}
     * for a real move (so the load and the write happen in the same transaction).
     */
    private WorkflowMovePreviewDto performMove(String assessmentId, String targetWorkflowId, boolean dryRun, WorkflowCatalog catalog) {
        Assessment assessment = assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assessment not found with id: " + assessmentId));
        AssessmentWorkflow source = catalog.forAssessment(assessment);
        AssessmentWorkflow target = checkMove(assessment, targetWorkflowId, catalog);

        boolean started = assessment.getStartDate() != null && !assessment.getStartDate().isAfter(LocalDateTime.now());
        String fromWorkflowId = assessment.getWorkflowId();
        String fromStatus = assessment.getStatus();
        String toStatus = mappingService.mapAssessmentStatus(fromStatus, source, target, started);

        List<Vulnerability> findings = vulnerabilityRepository.findByAssessmentIdAndDeletedAtIsNull(assessmentId);
        Map<List<String>, WorkflowMovePreviewDto.StatusChange> statusChanges = new LinkedHashMap<>();
        List<Vulnerability> mappedFindings = new ArrayList<>();
        int dueDateChanges = 0;
        for (Vulnerability finding : findings) {
            // Mapped on a copy of the fields mapping reads and writes, so a dry run never touches the entity.
            Vulnerability probe = Vulnerability.builder()
                    .severity(finding.getSeverity())
                    .status(finding.getStatus())
                    .openedAt(finding.getOpenedAt())
                    .closedAt(finding.getClosedAt())
                    .deletedAt(finding.getDeletedAt())
                    .exceptionExpiryDate(finding.getExceptionExpiryDate())
                    .dueAt(finding.getDueAt())
                    .warningAt(finding.getWarningAt())
                    .build();
            mappingService.mapFinding(probe, target);
            boolean statusChanged = !Objects.equals(probe.getStatus(), finding.getStatus());
            boolean datesChanged = !Objects.equals(probe.getDueAt(), finding.getDueAt())
                    || !Objects.equals(probe.getWarningAt(), finding.getWarningAt());
            if (statusChanged) {
                WorkflowMovePreviewDto.StatusChange change = statusChanges.computeIfAbsent(
                        Arrays.asList(finding.getStatus(), probe.getStatus()),
                        key -> new WorkflowMovePreviewDto.StatusChange(finding.getStatus(), probe.getStatus(), 0));
                change.setCount(change.getCount() + 1);
            }
            if (datesChanged) {
                dueDateChanges++;
            }
            if (!dryRun && (statusChanged || datesChanged)) {
                finding.setStatus(probe.getStatus());
                finding.setDueAt(probe.getDueAt());
                finding.setWarningAt(probe.getWarningAt());
                mappedFindings.add(finding);
            }
        }

        List<String> findingIds = findings.stream().map(Vulnerability::getId).toList();
        List<VulnerabilityStageCompletion> completions = findingIds.isEmpty()
                ? List.of() : stageCompletionRepository.findByVulnerabilityIdIn(findingIds);
        Set<String> targetStageIds = AssessmentWorkflows.stages(target).stream()
                .map(RemediationStage::getId).collect(Collectors.toSet());
        Set<List<String>> taken = new HashSet<>();
        completions.forEach(c -> taken.add(Arrays.asList(c.getVulnerabilityId(), c.getStageId())));
        List<VulnerabilityStageCompletion> remapped = new ArrayList<>();
        int remappedCount = 0;
        int unmappedCount = 0;
        for (VulnerabilityStageCompletion completion : completions) {
            if (targetStageIds.contains(completion.getStageId())) {
                continue;
            }
            Optional<String> targetStageId = mappingService.remapStageId(completion.getStageId(), source, target);
            if (targetStageId.isPresent() && taken.add(Arrays.asList(completion.getVulnerabilityId(), targetStageId.get()))) {
                remappedCount++;
                if (!dryRun) {
                    completion.setStageId(targetStageId.get());
                    remapped.add(completion);
                }
            } else {
                unmappedCount++;
            }
        }

        if (!dryRun) {
            // Already running inside transactionTemplate's transaction (see move(String, String,
            // boolean, WorkflowCatalog)) — the load above and this write are one transaction, so a
            // concurrent edit made in between cannot be silently overwritten by this method's own
            // in-memory copy.
            assessment.setWorkflowId(target.getId());
            assessment.setStatus(toStatus);
            assessment.setUpdatedAt(LocalDateTime.now());
            assessmentRepository.save(assessment);
            for (int from = 0; from < mappedFindings.size(); from += SAVE_BATCH) {
                vulnerabilityRepository.saveAll(mappedFindings.subList(from, Math.min(from + SAVE_BATCH, mappedFindings.size())));
                vulnerabilityRepository.flush();
            }
            stageCompletionRepository.saveAll(remapped);
        }

        return WorkflowMovePreviewDto.builder()
                .assessmentId(assessmentId)
                .fromWorkflowId(fromWorkflowId)
                .toWorkflowId(target.getId())
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .findingCount(findings.size())
                .findingStatusChanges(new ArrayList<>(statusChanges.values()))
                .dueDateChanges(dueDateChanges)
                .remappedStageCompletions(remappedCount)
                .unmappedStageCompletions(unmappedCount)
                .applied(!dryRun)
                .build();
    }
}
