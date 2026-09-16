package com.faction.clientportal.service;

import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.RemediationStage;
import com.faction.clientportal.model.Vulnerability;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The one place a finding is mapped onto another workflow: carrying a finding into an assessment on
 * another workflow now, moving an assessment between workflows (`AssessmentWorkflowMoveService`). Does not save.
 */
@Service
@RequiredArgsConstructor
public class WorkflowMappingService {

    /** Where a status the target workflow doesn't know goes. */
    static final String UNMAPPED_STATUS = "Open";

    private final SlaService slaService;

    /**
     * Keeps a built-in status and any status the target workflow has; any other status becomes Open.
     * Then recalculates the stored SLA dates under the target from the finding's original opened date.
     */
    public void mapFinding(Vulnerability finding, AssessmentWorkflow target) {
        String status = finding.getStatus();
        if (status != null && !AssessmentWorkflows.isBuiltInVulnerabilityStatus(status)) {
            List<String> targetStatuses = target.getVulnerabilityStatuses();
            if (targetStatuses == null || !targetStatuses.contains(status)) {
                finding.setStatus(UNMAPPED_STATUS);
            }
        }
        slaService.refresh(finding, target);
    }

    /**
     * An assessment's status on the target workflow. Kept when the target has it. Otherwise one of the
     * source's New / In Progress / Completed statuses becomes the target's status for the same role. Anything
     * else becomes the target's In Progress status once the assessment has started, or its New status before.
     */
    public String mapAssessmentStatus(String status, AssessmentWorkflow source, AssessmentWorkflow target, boolean started) {
        List<String> targetStatuses = target.getStatuses();
        if (status != null && targetStatuses != null && targetStatuses.contains(status)) {
            return status;
        }
        if (status != null && source != null) {
            if (status.equals(source.getNewAssessmentStatus())) {
                return target.getNewAssessmentStatus();
            }
            if (status.equals(source.getInProgressStatus())) {
                return target.getInProgressStatus();
            }
            if (status.equals(source.getCompletedStatus())) {
                return target.getCompletedStatus();
            }
        }
        return started ? target.getInProgressStatus() : target.getNewAssessmentStatus();
    }

    /**
     * Where a stage completion recorded under the source workflow belongs on the target: the same stage id
     * when the target has it, otherwise the target's stage with the same name. Empty when the target has
     * neither; the completion then stays as it is, kept as history but not shown.
     */
    public Optional<String> remapStageId(String stageId, AssessmentWorkflow source, AssessmentWorkflow target) {
        List<RemediationStage> targetStages = AssessmentWorkflows.stages(target);
        if (targetStages.stream().anyMatch(stage -> stage.getId().equals(stageId))) {
            return Optional.of(stageId);
        }
        return AssessmentWorkflows.stages(source).stream()
                .filter(stage -> stage.getId().equals(stageId))
                .map(RemediationStage::getName)
                .findFirst()
                .flatMap(name -> targetStages.stream()
                        .filter(stage -> name.equals(stage.getName()))
                        .map(RemediationStage::getId)
                        .findFirst());
    }
}
