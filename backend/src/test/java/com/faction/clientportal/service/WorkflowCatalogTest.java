package com.faction.clientportal.service;

import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentType;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.RemediationStage;
import com.faction.clientportal.repository.CompletedStatusFilter;
import com.faction.clientportal.testsupport.TestWorkflows;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One snapshot of every workflow: each assessment, type or id resolves to its workflow — anything
 * missing or unknown to Default Workflow, so a stray value never breaks a read — and the lists that
 * span workflows merge statuses and stages by name.
 */
class WorkflowCatalogTest {

    private final AssessmentWorkflow defaults = AssessmentWorkflow.defaultWorkflowBuilder().build();
    private final AssessmentWorkflow second = TestWorkflows.secondWorkflow();
    /** Sorts before "Second Workflow"; repeats one Default Workflow status and one second-workflow stage. */
    private final AssessmentWorkflow alpha = AssessmentWorkflow.builder()
            .id("alpha").name("alpha pentest")
            .statuses(new ArrayList<>(List.of("Testing", "Alpha Only")))
            .completedStatus("Alpha Only")
            .vulnerabilityStatuses(new ArrayList<>(List.of("Risk Accepted", "Deferred")))
            .remediationStages(new ArrayList<>(List.of(new RemediationStage("alpha-qa", "QA"))))
            .build();
    private final AssessmentWorkflow archived = AssessmentWorkflow.builder()
            .id("old").name("Old Workflow").archived(true)
            .statuses(new ArrayList<>(List.of("Legacy")))
            .completedStatus("Legacy")
            .vulnerabilityStatuses(new ArrayList<>(List.of("Legacy Finding")))
            .remediationStages(new ArrayList<>(List.of(new RemediationStage("old-stage", "Legacy Stage"))))
            .build();

    private final WorkflowCatalog catalog = WorkflowCatalog.of(List.of(second, archived, alpha, defaults));

    @Test
    void resolvesByIdAndFallsBackToDefaultWorkflow() {
        assertThat(catalog.forId(TestWorkflows.SECOND_ID)).isSameAs(second);
        assertThat(catalog.forId("no-such-workflow")).isSameAs(defaults);
        assertThat(catalog.forId(null)).isSameAs(defaults);
        assertThat(catalog.defaultWorkflow()).isSameAs(defaults);
    }

    @Test
    void resolvesAssessmentsAndTypesByTheirWorkflowId() {
        assertThat(catalog.forAssessment(Assessment.builder().workflowId(TestWorkflows.SECOND_ID).build())).isSameAs(second);
        assertThat(catalog.forAssessment(Assessment.builder().build())).isSameAs(defaults);
        assertThat(catalog.forAssessment(Assessment.builder().workflowId(null).build())).isSameAs(defaults);
        assertThat(catalog.forAssessment(null)).isSameAs(defaults);
        assertThat(catalog.forType(AssessmentType.builder().workflowId("alpha").build())).isSameAs(alpha);
        assertThat(catalog.forType(AssessmentType.builder().workflowId("gone").build())).isSameAs(defaults);
        assertThat(catalog.forType(null)).isSameAs(defaults);
    }

    @Test
    void archivedWorkflowsStillResolve() {
        assertThat(catalog.forId("old")).isSameAs(archived);
    }

    @Test
    void listsDefaultWorkflowFirstThenTheOthersByName() {
        assertThat(catalog.workflows(false)).containsExactly(defaults, alpha, second);
        assertThat(catalog.workflows(true)).containsExactly(defaults, alpha, archived, second);
    }

    @Test
    void mergesStatusNamesKeepingTheFirstOccurrence() {
        List<String> expected = new ArrayList<>(defaults.getStatuses());
        expected.add("Alpha Only");
        expected.addAll(second.getStatuses());

        assertThat(catalog.allStatusNames(false)).containsExactlyElementsOf(expected);
        assertThat(catalog.allStatusNames(true)).contains("Legacy").doesNotHaveDuplicates();
    }

    @Test
    void mergesAdditionalVulnerabilityStatusesAndStageNames() {
        // Default Workflow adds none; alpha (sorted before the second workflow) lists them first.
        assertThat(catalog.allVulnerabilityStatusNames(false)).containsExactly("Risk Accepted", "Deferred");
        assertThat(catalog.allVulnerabilityStatusNames(true)).contains("Legacy Finding");
        assertThat(catalog.allStageNames(false)).containsExactly("Development", "Staging", "Production", "QA", "Live");
        assertThat(catalog.allStageNames(true)).contains("Legacy Stage");
    }

    @Test
    void completedPairsCoverEveryWorkflowIncludingArchivedOnes() {
        WorkflowCatalog.CompletedPairs pairs = catalog.completedPairs();

        assertThat(pairs.workflowIds()).containsExactly("default", "alpha", "old", TestWorkflows.SECOND_ID);
        assertThat(pairs.completedStatuses()).containsExactly("Completed", "Alpha Only", "Legacy", "Signed Off");
    }

    @Test
    void completedStatusFilterPairsEachWorkflowWithItsCompletedStatusAndKnowsEveryId() {
        AssessmentWorkflow noCompleted = AssessmentWorkflow.builder()
                .id("blank").name("Blank").completedStatus(" ").build();
        WorkflowCatalog catalog = WorkflowCatalog.of(List.of(second, defaults, noCompleted));

        CompletedStatusFilter filter = catalog.completedStatusFilter();

        assertThat(filter.workflowIds()).containsExactly("default", TestWorkflows.SECOND_ID);
        assertThat(filter.completedStatuses()).containsExactly("Completed", "Signed Off");
        assertThat(filter.knownWorkflowIds()).containsExactly("default", "blank", TestWorkflows.SECOND_ID);
        assertThat(filter.defaultWorkflowId()).isEqualTo("default");
    }

    @Test
    void theWorkflowWithTheDefaultIdWinsOverAnotherFlaggedOne() {
        AssessmentWorkflow flaggedElsewhere = AssessmentWorkflow.builder()
                .id("elsewhere").name("Elsewhere").defaultWorkflow(true).build();

        WorkflowCatalog catalog = WorkflowCatalog.of(List.of(flaggedElsewhere, defaults));

        assertThat(catalog.defaultWorkflow()).isSameAs(defaults);
    }

    @Test
    void withNoDefaultIdTheFlaggedWorkflowIsDefaultWorkflow() {
        AssessmentWorkflow flaggedElsewhere = AssessmentWorkflow.builder()
                .id("elsewhere").name("Elsewhere").defaultWorkflow(true).build();

        WorkflowCatalog catalog = WorkflowCatalog.of(List.of(second, flaggedElsewhere));

        assertThat(catalog.defaultWorkflow()).isSameAs(flaggedElsewhere);
    }

    @Test
    void withNoFlaggedWorkflowTheOneWithTheDefaultIdIsDefaultWorkflow() {
        AssessmentWorkflow unflagged = AssessmentWorkflow.defaultWorkflowBuilder().defaultWorkflow(false).build();

        WorkflowCatalog fallback = WorkflowCatalog.of(List.of(second, unflagged));

        assertThat(fallback.defaultWorkflow()).isSameAs(unflagged);
        assertThat(fallback.workflows(true)).containsExactly(unflagged, second);
        assertThat(fallback.forId("nope")).isSameAs(unflagged);
    }

    @Test
    void aCatalogWithoutDefaultWorkflowIsRefused() {
        assertThatThrownBy(() -> WorkflowCatalog.of(List.of(second)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
