package com.faction.clientportal.service;

import com.faction.clientportal.exception.WorkflowConflictException;
import com.faction.clientportal.exception.WorkflowConflictException.Violation;
import com.faction.clientportal.model.WorkflowRenameTask;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.repository.VulnerabilityStageCompletionRepository;
import com.faction.clientportal.repository.WorkflowRenameTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Refuses a workflow edit that removes a status, vulnerability status or remediation stage still in use
 * on that workflow, or changes a vulnerability status a running rename is still writing.
 */
@Component
@RequiredArgsConstructor
public class WorkflowSettingsGuard {

    private final AssessmentRepository assessmentRepository;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final VulnerabilityStageCompletionRepository stageCompletionRepository;
    private final WorkflowRenameTaskRepository renameTaskRepository;

    /** @throws WorkflowConflictException listing every violation, when there is at least one */
    public void check(String workflowId, WorkflowSettingsChange change) {
        List<Violation> violations = new ArrayList<>();
        for (String status : change.removedStatuses()) {
            long count = assessmentRepository.countByWorkflowIdAndStatusAndDeletedAtIsNull(workflowId, status);
            if (count > 0) {
                violations.add(new Violation(WorkflowConflictException.ASSESSMENT_STATUS_IN_USE, status, count));
            }
        }
        for (String status : change.removedVulnerabilityStatuses()) {
            long count = vulnerabilityRepository.countInWorkflowWithStatus(workflowId, status);
            if (count > 0) {
                violations.add(new Violation(WorkflowConflictException.VULNERABILITY_STATUS_IN_USE, status, count));
            }
        }
        change.removedStages().forEach((stageId, stageName) -> {
            long count = stageCompletionRepository.countByStageId(stageId);
            if (count > 0) {
                violations.add(new Violation(WorkflowConflictException.REMEDIATION_STAGE_IN_USE, stageName, count));
            }
        });

        Set<String> beingWritten = renameTaskRepository
                .findByWorkflowIdAndState(workflowId, WorkflowRenameTask.State.RUNNING).stream()
                .map(WorkflowRenameTask::getToName)
                .collect(Collectors.toSet());
        Set<String> touched = new LinkedHashSet<>(change.removedVulnerabilityStatuses());
        touched.addAll(change.renamedVulnerabilityStatuses().keySet());
        for (String status : touched) {
            if (beingWritten.contains(status)) {
                violations.add(new Violation(WorkflowConflictException.RENAME_IN_PROGRESS, status, 0));
            }
        }

        if (!violations.isEmpty()) {
            throw new WorkflowConflictException(violations);
        }
    }
}
