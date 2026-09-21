package com.faction.clientportal.service;

import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.RemediationStage;
import com.faction.clientportal.testsupport.TestWorkflows;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules a single assessment's code applies with that assessment's own workflow. */
class AssessmentWorkflowsTest {

    private final AssessmentWorkflow defaults = AssessmentWorkflow.defaultWorkflowBuilder().build();
    private final AssessmentWorkflow second = TestWorkflows.secondWorkflow();

    @Test
    void onlyTheWorkflowsOwnCompletedStatusIsCompleted() {
        assertThat(AssessmentWorkflows.isCompleted(defaults, "Completed")).isTrue();
        assertThat(AssessmentWorkflows.isCompleted(defaults, "Signed Off")).isFalse();
        assertThat(AssessmentWorkflows.isCompleted(second, "Signed Off")).isTrue();
        assertThat(AssessmentWorkflows.isCompleted(second, "Completed")).isFalse();
        assertThat(AssessmentWorkflows.isCompleted(defaults, "COMPLETED")).isFalse();
        assertThat(AssessmentWorkflows.isCompleted(defaults, null)).isFalse();
    }

    @Test
    void stagesAreTheWorkflowsOwnOrTheDefaultsWhenItHasNone() {
        assertThat(AssessmentWorkflows.stages(second)).extracting(RemediationStage::getId)
                .containsExactly("second-qa", "second-live");
        AssessmentWorkflow empty = AssessmentWorkflow.builder().id("empty").name("Empty")
                .remediationStages(new ArrayList<>()).build();
        assertThat(AssessmentWorkflows.stages(empty)).isEqualTo(AssessmentWorkflow.defaultRemediationStages());
        AssessmentWorkflow none = AssessmentWorkflow.builder().id("none").name("None")
                .remediationStages(null).build();
        assertThat(AssessmentWorkflows.stages(none)).isEqualTo(AssessmentWorkflow.defaultRemediationStages());
    }

    @Test
    void theBuiltInVulnerabilityStatusesAreSharedByEveryWorkflow() {
        assertThat(AssessmentWorkflows.BUILT_IN_VULNERABILITY_STATUSES).containsExactly(
                "None", "Open", "Closed", "Past Due", "Exception",
                RetestService.STATUS_IN_RETEST, RetestService.STATUS_PASSED_RETEST, RetestService.STATUS_FAILED_RETEST);
        assertThat(AssessmentWorkflows.isBuiltInVulnerabilityStatus("Past Due")).isTrue();
        assertThat(AssessmentWorkflows.isBuiltInVulnerabilityStatus("Risk Accepted")).isFalse();
        assertThat(AssessmentWorkflows.isBuiltInVulnerabilityStatus(null)).isFalse();
    }
}
