package com.faction.clientportal.service;

import com.faction.clientportal.dto.workflow.UpdateWorkflowRequest;
import com.faction.clientportal.dto.workflow.UpdateWorkflowRequest.NamedEntry;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.RemediationStage;
import com.faction.clientportal.testsupport.TestWorkflows;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Turning a workflow edit into what changed. A row is tracked by its original name, so a rename is not
 * a removal plus an addition, and the guards and renames see exactly what the admin did.
 */
class WorkflowSettingsChangeTest {

    private final AssessmentWorkflow second = TestWorkflows.secondWorkflow();

    /** The second workflow's settings as an unchanged edit. */
    private UpdateWorkflowRequest.UpdateWorkflowRequestBuilder unchanged() {
        return UpdateWorkflowRequest.builder()
                .statuses(entries("Draft", "Scoping", "Fieldwork", "Signed Off"))
                .newAssessmentStatus("Draft")
                .inProgressStatus("Fieldwork")
                .completedStatus("Signed Off")
                .statusColors(new HashMap<>(Map.of("Draft", "#6b7280", "Signed Off", "#16a34a")))
                .vulnerabilitySlas(new ArrayList<>(second.getVulnerabilitySlas()))
                .vulnerabilityStatuses(entries("Risk Accepted"))
                .remediationStages(new ArrayList<>(second.getRemediationStages()))
                .allowSelfPeerReview(true);
    }

    private static List<NamedEntry> entries(String... names) {
        List<NamedEntry> list = new ArrayList<>();
        for (String name : names) {
            list.add(new NamedEntry(name, name));
        }
        return list;
    }

    @Test
    void anUnchangedEditChangesNothing() {
        WorkflowSettingsChange change = WorkflowSettingsChange.of(second, unchanged().build());

        assertThat(change.renamedStatuses()).isEmpty();
        assertThat(change.removedStatuses()).isEmpty();
        assertThat(change.renamedVulnerabilityStatuses()).isEmpty();
        assertThat(change.removedVulnerabilityStatuses()).isEmpty();
        assertThat(change.removedStages()).isEmpty();
    }

    @Test
    void aRowWithANewNameIsARenameNotARemoval() {
        List<NamedEntry> statuses = entries("Draft", "Scoping", "Signed Off");
        statuses.add(2, new NamedEntry("Fieldwork", "On Site"));
        List<NamedEntry> vulnerabilityStatuses = List.of(new NamedEntry("Risk Accepted", "Accepted Risk"));

        WorkflowSettingsChange change = WorkflowSettingsChange.of(second, unchanged()
                .statuses(statuses).inProgressStatus("On Site")
                .vulnerabilityStatuses(vulnerabilityStatuses).build());

        assertThat(change.renamedStatuses()).containsExactlyEntriesOf(Map.of("Fieldwork", "On Site"));
        assertThat(change.removedStatuses()).isEmpty();
        assertThat(change.renamedVulnerabilityStatuses()).containsExactlyEntriesOf(Map.of("Risk Accepted", "Accepted Risk"));
        assertThat(change.removedVulnerabilityStatuses()).isEmpty();
    }

    @Test
    void anOriginalRowNoEntryPointsAtIsRemovedAndANewRowIsNot() {
        List<NamedEntry> statuses = entries("Draft", "Fieldwork", "Signed Off");
        statuses.add(new NamedEntry(null, "Retest"));
        List<RemediationStage> stages = List.of(new RemediationStage("second-live", "Live"));

        WorkflowSettingsChange change = WorkflowSettingsChange.of(second, unchanged()
                .statuses(statuses).vulnerabilityStatuses(List.of()).remediationStages(stages).build());

        assertThat(change.removedStatuses()).containsExactly("Scoping");
        assertThat(change.removedVulnerabilityStatuses()).containsExactly("Risk Accepted");
        assertThat(change.removedStages()).containsExactlyEntriesOf(Map.of("second-qa", "QA"));
    }

    @Test
    void theNewInProgressAndCompletedStatusesMustBeInTheList() {
        assertThatThrownBy(() -> WorkflowSettingsChange.of(second, unchanged().completedStatus("Done").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Done");
    }

    @Test
    void blankOrDuplicateNamesAreRefused() {
        List<NamedEntry> blank = entries("Draft", "Fieldwork", "Signed Off");
        blank.add(new NamedEntry(null, "  "));
        List<NamedEntry> duplicate = entries("Draft", "Fieldwork", "Signed Off");
        duplicate.add(new NamedEntry(null, "Draft"));

        assertThatThrownBy(() -> WorkflowSettingsChange.of(second, unchanged().statuses(blank).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkflowSettingsChange.of(second, unchanged().statuses(duplicate).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Draft");
    }

    @Test
    void oneOriginalRowRenamedTwiceIsRefused() {
        List<NamedEntry> statuses = entries("Draft", "Signed Off");
        statuses.add(new NamedEntry("Fieldwork", "On Site"));
        statuses.add(new NamedEntry("Fieldwork", "Remote"));

        assertThatThrownBy(() -> WorkflowSettingsChange.of(second, unchanged()
                .statuses(statuses).inProgressStatus("On Site").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Fieldwork");
    }

    @Test
    void swappingTwoStatusNamesInOneEditIsRefused() {
        List<NamedEntry> statuses = entries("Draft", "Signed Off");
        statuses.add(new NamedEntry("Scoping", "Fieldwork"));
        statuses.add(new NamedEntry("Fieldwork", "Scoping"));

        assertThatThrownBy(() -> WorkflowSettingsChange.of(second, unchanged().statuses(statuses).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Scoping").hasMessageContaining("Fieldwork");
    }

    @Test
    void chainingStatusRenamesInOneEditIsRefused() {
        List<NamedEntry> statuses = entries("Draft", "Signed Off");
        statuses.add(new NamedEntry("Scoping", "Fieldwork"));
        statuses.add(new NamedEntry("Fieldwork", "On Site"));

        assertThatThrownBy(() -> WorkflowSettingsChange.of(second, unchanged().statuses(statuses).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Scoping").hasMessageContaining("Fieldwork");
    }

    @Test
    void renamingOntoAStatusNameTheEditRemovesIsAccepted() {
        List<NamedEntry> statuses = entries("Draft", "Signed Off");
        statuses.add(new NamedEntry("Scoping", "Fieldwork"));

        WorkflowSettingsChange change = WorkflowSettingsChange.of(second, unchanged().statuses(statuses).build());

        assertThat(change.renamedStatuses()).containsExactlyEntriesOf(Map.of("Scoping", "Fieldwork"));
        assertThat(change.removedStatuses()).containsExactly("Fieldwork");
    }

    @Test
    void swappingTwoVulnerabilityStatusNamesInOneEditIsRefused() {
        AssessmentWorkflow current = TestWorkflows.secondWorkflow();
        current.setVulnerabilityStatuses(new ArrayList<>(List.of("Risk Accepted", "False Positive")));
        List<NamedEntry> vulnerabilityStatuses = List.of(
                new NamedEntry("Risk Accepted", "False Positive"),
                new NamedEntry("False Positive", "Risk Accepted"));

        assertThatThrownBy(() -> WorkflowSettingsChange.of(current, unchanged()
                .vulnerabilityStatuses(vulnerabilityStatuses).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Risk Accepted").hasMessageContaining("False Positive");
    }

    @Test
    void builtInVulnerabilityStatusesCannotBeAddedOrRenamedTo() {
        assertThatThrownBy(() -> WorkflowSettingsChange.of(second, unchanged()
                .vulnerabilityStatuses(List.of(new NamedEntry(null, "Past Due"))).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Past Due");
        assertThatThrownBy(() -> WorkflowSettingsChange.of(second, unchanged()
                .vulnerabilityStatuses(List.of(new NamedEntry("Risk Accepted", "Closed"))).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Closed");
    }

    @Test
    void anEmptyOrBlankNamedStageListIsRefusedByATrackedEdit() {
        assertThatThrownBy(() -> WorkflowSettingsChange.of(second, unchanged().remediationStages(List.of()).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one remediation stage");
        assertThatThrownBy(() -> WorkflowSettingsChange.of(second, unchanged().remediationStages(null).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one remediation stage");
        assertThatThrownBy(() -> WorkflowSettingsChange.of(second, unchanged()
                .remediationStages(List.of(new RemediationStage("second-qa", "  "))).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be blank");
    }

    @Test
    void aNullVulnerabilitySlasListIsRefusedByATrackedEditButAnEmptyOneIsAllowed() {
        assertThatThrownBy(() -> WorkflowSettingsChange.of(second, unchanged().vulnerabilitySlas(null).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vulnerabilitySlas is required");

        WorkflowSettingsChange change = WorkflowSettingsChange.of(second, unchanged().vulnerabilitySlas(List.of()).build());

        assertThat(change.removedStatuses()).isEmpty();
    }

    @Test
    void anUntrackedSaveTreatsEveryMissingNameAsRemovedAndRenamesNothing() {
        AssessmentWorkflow submitted = TestWorkflows.secondWorkflow();
        submitted.setStatuses(new ArrayList<>(List.of("Draft", "On Site", "Signed Off")));
        submitted.setVulnerabilityStatuses(new ArrayList<>(List.of("Open", "Deferred")));
        submitted.setRemediationStages(new ArrayList<>(List.of(new RemediationStage("second-qa", "QA"))));

        WorkflowSettingsChange change = WorkflowSettingsChange.untracked(second, submitted);

        assertThat(change.renamedStatuses()).isEmpty();
        assertThat(change.removedStatuses()).containsExactlyInAnyOrder("Scoping", "Fieldwork");
        assertThat(change.removedVulnerabilityStatuses()).containsExactly("Risk Accepted");
        assertThat(change.removedStages()).containsExactlyEntriesOf(Map.of("second-live", "Live"));
    }

    @Test
    void aSaveWithoutStagesKeepsTheDefaultStagesItWouldBeSavedWith() {
        AssessmentWorkflow defaults = AssessmentWorkflow.defaultWorkflowBuilder().build();
        AssessmentWorkflow submitted = AssessmentWorkflow.defaultWorkflowBuilder().remediationStages(null).build();

        assertThat(WorkflowSettingsChange.untracked(defaults, submitted).removedStages()).isEmpty();
    }

    @Test
    void settingsOfTrimsNamesAndMovesARenamedStatusColour() {
        List<NamedEntry> statuses = entries("Draft", "Scoping", "Fieldwork");
        statuses.add(new NamedEntry("Signed Off", " Complete "));
        UpdateWorkflowRequest request = unchanged().statuses(statuses).completedStatus("Complete").build();

        AssessmentWorkflow settings = WorkflowSettingsChange.settingsOf(request, WorkflowSettingsChange.of(second, request));

        assertThat(settings.getStatuses()).containsExactly("Draft", "Scoping", "Fieldwork", "Complete");
        assertThat(settings.getCompletedStatus()).isEqualTo("Complete");
        assertThat(settings.getStatusColors()).containsEntry("Complete", "#16a34a").doesNotContainKey("Signed Off");
        assertThat(settings.getVulnerabilityStatuses()).containsExactly("Risk Accepted");
        assertThat(settings.isAllowSelfPeerReview()).isTrue();
    }
}
