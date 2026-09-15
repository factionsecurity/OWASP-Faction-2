package com.faction.clientportal.service;

import com.faction.clientportal.dto.workflow.CreateWorkflowRequest;
import com.faction.clientportal.dto.workflow.UpdateWorkflowRequest;
import com.faction.clientportal.dto.workflow.WorkflowDto;
import com.faction.clientportal.dto.workflow.WorkflowUsageDto;
import com.faction.clientportal.edition.EditionPolicy;
import com.faction.clientportal.edition.Feature;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.exception.WorkflowConflictException;
import com.faction.clientportal.exception.WorkflowConflictException.Violation;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.RemediationStage;
import com.faction.clientportal.model.WorkflowRenameTask;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.AssessmentWorkflowRepository;
import com.faction.clientportal.repository.WorkflowRenameTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Managing workflows as a set: listing, usage counts, copying one into a new workflow, archiving and
 * deleting. Edits to one workflow's settings go through {@link WorkflowEditService}.
 */
@Service
@RequiredArgsConstructor
public class WorkflowAdminService {

    private final AssessmentWorkflowRepository workflowRepository;
    private final WorkflowCatalogService workflowCatalogService;
    private final WorkflowEditService workflowEditService;
    private final AssessmentRepository assessmentRepository;
    private final AssessmentTypeRepository assessmentTypeRepository;
    private final WorkflowRenameTaskRepository renameTaskRepository;
    private final EmailNotificationConfigService emailNotificationConfigService;
    private final EditionPolicy editionPolicy;

    /** Default Workflow first, then the others by name; archived ones only when asked. */
    public List<WorkflowDto> list(boolean includeArchived) {
        Map<String, List<WorkflowRenameTask>> running = runningRenamesByWorkflow();
        return workflowCatalogService.load().workflows(includeArchived).stream()
                .map(workflow -> toDto(workflow, running.getOrDefault(workflow.getId(), List.of())))
                .toList();
    }

    /** Every workflow's assessment type and assessment counts, archived workflows included. */
    public List<WorkflowUsageDto> usage() {
        Map<String, Long> types = counts(assessmentTypeRepository.countGroupedByWorkflowId());
        Map<String, Long> assessments = counts(assessmentRepository.countGroupedByWorkflowId());
        return workflowCatalogService.load().workflows(true).stream()
                .map(w -> new WorkflowUsageDto(w.getId(),
                        types.getOrDefault(w.getId(), 0L), assessments.getOrDefault(w.getId(), 0L)))
                .toList();
    }

    public WorkflowDto get(String id) {
        return toDto(find(id), runningRenamesByWorkflow().getOrDefault(id, List.of()));
    }

    /** A new workflow copied from an existing one, with fresh stage ids that inherit the source stages' email settings. */
    public WorkflowDto create(CreateWorkflowRequest request) {
        editionPolicy.require(Feature.CUSTOM_WORKFLOWS);
        AssessmentWorkflow source = workflowRepository.findById(request.getSourceWorkflowId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown source workflow: " + request.getSourceWorkflowId()));
        String name = request.getName() == null ? "" : request.getName().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("A workflow name cannot be blank");
        }
        if (workflowRepository.existsByNameIgnoreCase(name)) {
            throw new WorkflowConflictException(new Violation(WorkflowConflictException.NAME_TAKEN, name, 0));
        }

        Map<String, String> newStageIdByOld = new LinkedHashMap<>();
        List<RemediationStage> stages = new ArrayList<>();
        for (RemediationStage stage : AssessmentWorkflows.stages(source)) {
            String newId = UUID.randomUUID().toString();
            newStageIdByOld.put(stage.getId(), newId);
            stages.add(new RemediationStage(newId, stage.getName()));
        }
        LocalDateTime now = LocalDateTime.now();
        AssessmentWorkflow copy = workflowRepository.save(AssessmentWorkflow.builder()
                .id(UUID.randomUUID().toString())
                .name(name)
                .defaultWorkflow(false)
                .archived(false)
                .statuses(source.getStatuses() == null ? new ArrayList<>() : new ArrayList<>(source.getStatuses()))
                .newAssessmentStatus(source.getNewAssessmentStatus())
                .inProgressStatus(source.getInProgressStatus())
                .completedStatus(source.getCompletedStatus())
                .statusColors(source.getStatusColors() == null ? new HashMap<>() : new HashMap<>(source.getStatusColors()))
                .vulnerabilitySlas(source.getVulnerabilitySlas() == null
                        ? new ArrayList<>() : new ArrayList<>(source.getVulnerabilitySlas()))
                .vulnerabilityStatuses(source.getVulnerabilityStatuses() == null
                        ? new ArrayList<>() : new ArrayList<>(source.getVulnerabilityStatuses()))
                .remediationStages(stages)
                .allowSelfPeerReview(source.isAllowSelfPeerReview())
                .createdAt(now)
                .updatedAt(now)
                .build());
        emailNotificationConfigService.copyStageSettings(newStageIdByOld);
        return toDto(copy, List.of());
    }

    public WorkflowDto update(String id, UpdateWorkflowRequest request) {
        AssessmentWorkflow saved = workflowEditService.update(id, request);
        return toDto(saved, runningRenamesByWorkflow().getOrDefault(id, List.of()));
    }

    /** Hides a workflow from pickers; its assessments keep working. Refused for Default Workflow or while a type uses it. */
    public WorkflowDto archive(String id) {
        AssessmentWorkflow workflow = find(id);
        refuseForDefault(workflow);
        long types = assessmentTypeRepository.countByWorkflowId(id);
        if (types > 0) {
            throw new WorkflowConflictException(new Violation(
                    WorkflowConflictException.USED_BY_ASSESSMENT_TYPES, workflow.getName(), types));
        }
        return setArchived(workflow, true);
    }

    public WorkflowDto unarchive(String id) {
        return setArchived(find(id), false);
    }

    /** Deletes an unused workflow, with all of its rename records and its stages' email settings (only the stage ids no remaining workflow still uses). */
    public void delete(String id) {
        AssessmentWorkflow workflow = find(id);
        refuseForDefault(workflow);
        List<Violation> violations = new ArrayList<>();
        long types = assessmentTypeRepository.countByWorkflowId(id);
        if (types > 0) {
            violations.add(new Violation(WorkflowConflictException.USED_BY_ASSESSMENT_TYPES, workflow.getName(), types));
        }
        long assessments = assessmentRepository.countByWorkflowIdAndDeletedAtIsNull(id);
        if (assessments > 0) {
            violations.add(new Violation(WorkflowConflictException.USED_BY_ASSESSMENTS, workflow.getName(), assessments));
        }
        if (!violations.isEmpty()) {
            throw new WorkflowConflictException(violations);
        }
        Set<String> sharedStageIds = workflowRepository.findAll().stream()
                .filter(w -> !id.equals(w.getId()))
                .flatMap(w -> AssessmentWorkflows.stages(w).stream())
                .map(RemediationStage::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<String> stageIdsToClear = AssessmentWorkflows.stages(workflow).stream()
                .map(RemediationStage::getId)
                .filter(Objects::nonNull)
                .filter(stageId -> !sharedStageIds.contains(stageId))
                .toList();
        renameTaskRepository.deleteByWorkflowId(id);
        emailNotificationConfigService.removeStageSettings(stageIdsToClear);
        workflowRepository.delete(workflow);
    }

    private AssessmentWorkflow find(String id) {
        return workflowRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow not found with id: " + id));
    }

    private static void refuseForDefault(AssessmentWorkflow workflow) {
        if (AssessmentWorkflow.DEFAULT_ID.equals(workflow.getId()) || workflow.isDefaultWorkflow()) {
            throw new WorkflowConflictException(new Violation(
                    WorkflowConflictException.DEFAULT_WORKFLOW, workflow.getName(), 0));
        }
    }

    private WorkflowDto setArchived(AssessmentWorkflow workflow, boolean archived) {
        workflow.setArchived(archived);
        workflow.setUpdatedAt(LocalDateTime.now());
        AssessmentWorkflow saved = workflowRepository.save(workflow);
        return toDto(saved, runningRenamesByWorkflow().getOrDefault(saved.getId(), List.of()));
    }

    private Map<String, List<WorkflowRenameTask>> runningRenamesByWorkflow() {
        return renameTaskRepository.findByState(WorkflowRenameTask.State.RUNNING).stream()
                .collect(Collectors.groupingBy(WorkflowRenameTask::getWorkflowId));
    }

    private static Map<String, Long> counts(List<Object[]> rows) {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    private static WorkflowDto toDto(AssessmentWorkflow workflow, List<WorkflowRenameTask> running) {
        return WorkflowDto.builder()
                .id(workflow.getId())
                .name(workflow.getName())
                .defaultWorkflow(workflow.isDefaultWorkflow())
                .archived(workflow.isArchived())
                .statuses(workflow.getStatuses())
                .newAssessmentStatus(workflow.getNewAssessmentStatus())
                .inProgressStatus(workflow.getInProgressStatus())
                .completedStatus(workflow.getCompletedStatus())
                .statusColors(workflow.getStatusColors())
                .vulnerabilitySlas(workflow.getVulnerabilitySlas())
                .vulnerabilityStatuses(workflow.getVulnerabilityStatuses())
                .builtInVulnerabilityStatuses(AssessmentWorkflows.BUILT_IN_VULNERABILITY_STATUSES)
                .remediationStages(AssessmentWorkflows.stages(workflow))
                .allowSelfPeerReview(workflow.isAllowSelfPeerReview())
                .createdAt(workflow.getCreatedAt())
                .updatedAt(workflow.getUpdatedAt())
                .renamesInProgress(running.stream()
                        .map(t -> new WorkflowDto.RenameInProgress(t.getFromName(), t.getToName(), t.getProcessed()))
                        .toList())
                .build();
    }
}
