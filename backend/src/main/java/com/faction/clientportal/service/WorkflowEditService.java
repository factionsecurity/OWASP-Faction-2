package com.faction.clientportal.service;

import com.faction.clientportal.dto.workflow.UpdateWorkflowRequest;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.exception.WorkflowConflictException;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.RemediationStage;
import com.faction.clientportal.model.WorkflowRenameTask;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentWorkflowRepository;
import com.faction.clientportal.repository.WorkflowRenameTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Saves an edit to one workflow. In one transaction: refuses removals still in use (and a name another
 * workflow has), renames assessment statuses on that workflow's assessments, saves the settings, and
 * records each vulnerability status rename. Only after that commits does it announce an SLA change and
 * start the vulnerability status renames, so neither ever reads settings that were not saved.
 */
@Service
@RequiredArgsConstructor
public class WorkflowEditService {

    private final AssessmentWorkflowRepository workflowRepository;
    private final AssessmentWorkflowConfigService workflowConfigService;
    private final WorkflowSettingsGuard settingsGuard;
    private final AssessmentRepository assessmentRepository;
    private final WorkflowRenameTaskRepository renameTaskRepository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;

    /** Refuses to run inside an existing transaction: its events must only publish after this method's own commit. */
    @Transactional(propagation = Propagation.NEVER)
    public AssessmentWorkflow update(String workflowId, UpdateWorkflowRequest request) {
        AssessmentWorkflow current = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow not found with id: " + workflowId));
        WorkflowSettingsChange change = WorkflowSettingsChange.of(current, request);
        AssessmentWorkflow settings = WorkflowSettingsChange.settingsOf(request, change);
        String name = request.getName() == null ? null : request.getName().trim();
        if (name != null && name.isEmpty()) {
            throw new IllegalArgumentException("A workflow name cannot be blank");
        }

        Set<String> foreignStageIds = workflowRepository.findAll().stream()
                .filter(w -> !workflowId.equals(w.getId()))
                .flatMap(w -> AssessmentWorkflows.stages(w).stream())
                .map(RemediationStage::getId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        for (RemediationStage stage : request.getRemediationStages()) {
            if (stage.getId() != null && !stage.getId().isBlank() && foreignStageIds.contains(stage.getId())) {
                throw new IllegalArgumentException(
                        "Remediation stage id " + stage.getId() + " belongs to another workflow");
            }
        }

        List<WorkflowRenameTask> renames = new ArrayList<>();
        AssessmentWorkflowConfigService.SettingsSave saved = transactionTemplate.execute(status -> {
            if (name != null && !name.equals(current.getName())) {
                if (workflowRepository.existsByNameIgnoreCaseAndIdNot(name, workflowId)) {
                    throw new WorkflowConflictException(new WorkflowConflictException.Violation(
                            WorkflowConflictException.NAME_TAKEN, name, 0));
                }
                current.setName(name);
            }
            settingsGuard.check(workflowId, change);
            for (WorkflowRenameTask running : renameTaskRepository
                    .findByWorkflowIdAndState(workflowId, WorkflowRenameTask.State.RUNNING)) {
                if (settings.getVulnerabilityStatuses().contains(running.getFromName())) {
                    throw new WorkflowConflictException(new WorkflowConflictException.Violation(
                            WorkflowConflictException.RENAME_IN_PROGRESS, running.getFromName(), 0));
                }
            }
            change.renamedStatuses().forEach((from, to) ->
                    assessmentRepository.renameStatusInWorkflow(workflowId, from, to));
            change.renamedVulnerabilityStatuses().forEach((from, to) ->
                    renames.add(renameTaskRepository.save(WorkflowRenameTask.start(workflowId, from, to))));
            return workflowConfigService.saveSettings(current, settings);
        });

        if (saved.slasChanged()) {
            eventPublisher.publishEvent(new SlaConfigChangedEvent(workflowId));
        }
        renames.forEach(task -> eventPublisher.publishEvent(new VulnerabilityStatusRenameRequested(task.getId())));
        return saved.workflow();
    }
}
