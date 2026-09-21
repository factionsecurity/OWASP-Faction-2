package com.faction.clientportal.testsupport;

import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.RemediationStage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The second workflow exists so that code reading the wrong workflow gives a visibly wrong answer.
 * That only works if it differs from Default Workflow in every setting and shares no status or stage.
 */
class TestWorkflowsTest {

    private final AssessmentWorkflow defaults = AssessmentWorkflow.defaultWorkflowBuilder().build();
    private final AssessmentWorkflow second = TestWorkflows.secondWorkflow();

    @Test
    void itIsANamedNonDefaultWorkflow() {
        assertThat(second.getId()).isEqualTo(TestWorkflows.SECOND_ID).isNotEqualTo(AssessmentWorkflow.DEFAULT_ID);
        assertThat(second.getName()).isEqualTo(TestWorkflows.SECOND_NAME).isNotEqualTo(AssessmentWorkflow.DEFAULT_NAME);
        assertThat(second.isDefaultWorkflow()).isFalse();
        assertThat(second.isArchived()).isFalse();
    }

    @Test
    void everySettingDiffersFromDefaultWorkflow() {
        assertThat(second.getStatuses()).doesNotContainAnyElementsOf(defaults.getStatuses());
        assertThat(second.getStatuses()).contains(
                second.getNewAssessmentStatus(), second.getInProgressStatus(), second.getCompletedStatus());
        assertThat(second.getNewAssessmentStatus()).isNotEqualTo(defaults.getNewAssessmentStatus());
        assertThat(second.getInProgressStatus()).isNotEqualTo(defaults.getInProgressStatus());
        assertThat(second.getCompletedStatus()).isNotEqualTo(defaults.getCompletedStatus());
        assertThat(second.getStatusColors()).isNotEqualTo(defaults.getStatusColors());
        assertThat(second.getVulnerabilitySlas()).doesNotContainAnyElementsOf(defaults.getVulnerabilitySlas());
        // Default Workflow has no additional vulnerability statuses, so the second one differs by having some.
        assertThat(second.getVulnerabilityStatuses()).isNotEmpty()
                .isNotEqualTo(defaults.getVulnerabilityStatuses());
        assertThat(second.getRemediationStages()).extracting(RemediationStage::getId)
                .doesNotContainAnyElementsOf(defaults.getRemediationStages().stream().map(RemediationStage::getId).toList());
        assertThat(second.getRemediationStages()).extracting(RemediationStage::getName)
                .doesNotContainAnyElementsOf(defaults.getRemediationStages().stream().map(RemediationStage::getName).toList());
        assertThat(second.isAllowSelfPeerReview()).isNotEqualTo(defaults.isAllowSelfPeerReview());
    }

    @Test
    void eachCallReturnsAFreshCopy() {
        TestWorkflows.secondWorkflow().getStatuses().add("Mutated");

        assertThat(TestWorkflows.secondWorkflow().getStatuses()).doesNotContain("Mutated");
    }
}
