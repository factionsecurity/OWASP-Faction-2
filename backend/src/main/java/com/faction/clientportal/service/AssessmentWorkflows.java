package com.faction.clientportal.service;

import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.RemediationStage;

import java.util.List;

/**
 * Rules that code acting on one assessment applies with that assessment's own workflow (resolve it
 * with {@link WorkflowCatalogService#forAssessment}). Cross-type readers still use Default Workflow
 * through {@link AssessmentWorkflowConfigService} until they merge workflows by name.
 */
public final class AssessmentWorkflows {

    /**
     * Vulnerability statuses every workflow shares. They are never removed, renamed or mapped away when
     * a finding moves between workflows. A workflow's own vulnerability statuses are additional to these.
     */
    public static final List<String> BUILT_IN_VULNERABILITY_STATUSES = List.of(
            "None", "Open", "Closed", "Past Due", "Exception",
            RetestService.STATUS_IN_RETEST, RetestService.STATUS_PASSED_RETEST, RetestService.STATUS_FAILED_RETEST);

    private AssessmentWorkflows() {
    }

    public static boolean isBuiltInVulnerabilityStatus(String status) {
        return status != null && BUILT_IN_VULNERABILITY_STATUSES.contains(status);
    }

    /** Whether {@code status} is the workflow's configured completed status. */
    public static boolean isCompleted(AssessmentWorkflow workflow, String status) {
        return status != null && workflow != null && status.equals(workflow.getCompletedStatus());
    }

    /**
     * The workflow's remediation stages, never empty: there must always be a terminal (last) stage for
     * closing a vulnerability, so a missing or empty list falls back to the defaults.
     */
    public static List<RemediationStage> stages(AssessmentWorkflow workflow) {
        List<RemediationStage> stages = workflow == null ? null : workflow.getRemediationStages();
        return stages == null || stages.isEmpty() ? AssessmentWorkflow.defaultRemediationStages() : stages;
    }
}
