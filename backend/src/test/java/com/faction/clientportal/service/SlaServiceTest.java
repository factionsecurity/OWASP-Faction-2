package com.faction.clientportal.service;

import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.VulnerabilitySla;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.testsupport.TestWorkflows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * SlaService is the only code that sets a finding's stored due and warning dates. Every expected
 * date here is worked out by hand (2026 is not a leap year) rather than computed.
 */
@ExtendWith(MockitoExtension.class)
class SlaServiceTest {

    private static final LocalDateTime OPENED = LocalDateTime.of(2026, 1, 1, 9, 0);
    /** HIGH under the default SLAs (60 days, warning 30 days before). */
    private static final LocalDateTime HIGH_DUE = LocalDateTime.of(2026, 3, 2, 9, 0);
    private static final LocalDateTime HIGH_WARNING = LocalDateTime.of(2026, 1, 31, 9, 0);

    private static final SlaService.SlaPolicy DEFAULTS =
            SlaService.SlaPolicy.of(AssessmentWorkflow.defaultVulnerabilitySlas());

    private static final java.time.LocalDateTime OPENED_2099 = java.time.LocalDateTime.of(2099, 1, 1, 9, 0);

    @Mock private AssessmentWorkflowConfigService workflowConfigService;
    @Mock private WorkflowCatalogService workflowCatalogService;
    @Mock private AssessmentRepository assessmentRepository;
    @InjectMocks private SlaService slaService;

    private static Vulnerability open(VulnerabilitySeverity severity) {
        return Vulnerability.builder()
                .id("v-1").severity(severity).status("Open").openedAt(OPENED).build();
    }

    private void configured(VulnerabilitySla... slas) {
        when(workflowConfigService.getConfig()).thenReturn(AssessmentWorkflow.defaultWorkflowBuilder()
                .vulnerabilitySlas(new ArrayList<>(Arrays.asList(slas))).build());
    }

    private void catalogOf(AssessmentWorkflow... workflows) {
        when(workflowCatalogService.load()).thenReturn(WorkflowCatalog.of(List.of(workflows)));
    }

    @Test
    void anOpenHighFindingIsDueSixtyDaysAfterOpeningAndWarnsThirtyDaysBefore() {
        Vulnerability v = open(VulnerabilitySeverity.HIGH);
        SlaService.apply(v, DEFAULTS);
        assertThat(v.getDueAt()).isEqualTo(HIGH_DUE);
        assertThat(v.getWarningAt()).isEqualTo(HIGH_WARNING);
    }

    @Test
    void eachSeverityUsesItsOwnSla() {
        Vulnerability critical = open(VulnerabilitySeverity.CRITICAL);
        Vulnerability medium = open(VulnerabilitySeverity.MEDIUM);
        SlaService.apply(critical, DEFAULTS);
        SlaService.apply(medium, DEFAULTS);
        assertThat(critical.getDueAt()).isEqualTo(LocalDateTime.of(2026, 1, 31, 9, 0));
        assertThat(critical.getWarningAt()).isEqualTo(LocalDateTime.of(2026, 1, 11, 9, 0));
        assertThat(medium.getDueAt()).isEqualTo(LocalDateTime.of(2027, 1, 1, 9, 0));
        assertThat(medium.getWarningAt()).isEqualTo(LocalDateTime.of(2026, 3, 7, 9, 0));
    }

    @Test
    void aClosedFindingHasNoDueDate() {
        Vulnerability v = open(VulnerabilitySeverity.HIGH);
        v.setStatus("Closed");
        v.setClosedAt(LocalDateTime.of(2026, 2, 1, 9, 0));
        SlaService.apply(v, DEFAULTS);
        assertThat(v.getDueAt()).isNull();
        assertThat(v.getWarningAt()).isNull();
    }

    @Test
    void aDeletedFindingHasNoDueDate() {
        Vulnerability v = open(VulnerabilitySeverity.HIGH);
        v.setDeletedAt(LocalDateTime.of(2026, 2, 1, 9, 0));
        SlaService.apply(v, DEFAULTS);
        assertThat(v.getDueAt()).isNull();
        assertThat(v.getWarningAt()).isNull();
    }

    @Test
    void anUnopenedFindingHasNoDueDate() {
        Vulnerability v = Vulnerability.builder().id("v-1").severity(VulnerabilitySeverity.HIGH).build();
        SlaService.apply(v, DEFAULTS);
        assertThat(v.getDueAt()).isNull();
        assertThat(v.getWarningAt()).isNull();
    }

    @Test
    void aSeverityWithoutAnSlaHasNoDueDate() {
        Vulnerability low = open(VulnerabilitySeverity.LOW);
        Vulnerability info = open(VulnerabilitySeverity.INFORMATIONAL);
        SlaService.apply(low, DEFAULTS);
        SlaService.apply(info, DEFAULTS);
        assertThat(low.getDueAt()).isNull();
        assertThat(low.getWarningAt()).isNull();
        assertThat(info.getDueAt()).isNull();
        assertThat(info.getWarningAt()).isNull();
    }

    @Test
    void aFindingWithoutASeverityHasNoDueDate() {
        Vulnerability v = open(null);
        SlaService.apply(v, DEFAULTS);
        assertThat(v.getDueAt()).isNull();
        assertThat(v.getWarningAt()).isNull();
    }

    @Test
    void anExceptionExpiringAfterTheSlaMovesTheDueDateToTheExpiry() {
        Vulnerability v = open(VulnerabilitySeverity.HIGH);
        v.setStatus("Exception");
        v.setExceptionExpiryDate(LocalDateTime.of(2026, 4, 15, 0, 0));
        SlaService.apply(v, DEFAULTS);
        assertThat(v.getDueAt()).isEqualTo(LocalDateTime.of(2026, 4, 15, 0, 0));
        // The warning window still opens relative to the SLA, not the exception.
        assertThat(v.getWarningAt()).isEqualTo(HIGH_WARNING);
    }

    @Test
    void anExceptionExpiringBeforeTheSlaKeepsTheSlaDueDate() {
        Vulnerability v = open(VulnerabilitySeverity.HIGH);
        v.setStatus("Exception");
        v.setExceptionExpiryDate(LocalDateTime.of(2026, 2, 1, 0, 0));
        SlaService.apply(v, DEFAULTS);
        assertThat(v.getDueAt()).isEqualTo(HIGH_DUE);
        assertThat(v.getWarningAt()).isEqualTo(HIGH_WARNING);
    }

    @Test
    void anExpiryOnAFindingThatIsNotUnderExceptionIsIgnored() {
        Vulnerability v = open(VulnerabilitySeverity.HIGH);
        v.setExceptionExpiryDate(LocalDateTime.of(2026, 4, 15, 0, 0));
        SlaService.apply(v, DEFAULTS);
        assertThat(v.getDueAt()).isEqualTo(HIGH_DUE);
    }

    @Test
    void closingAFindingClearsDatesItAlreadyHad() {
        Vulnerability v = open(VulnerabilitySeverity.HIGH);
        SlaService.apply(v, DEFAULTS);
        assertThat(v.getDueAt()).isEqualTo(HIGH_DUE);

        v.setStatus("Closed");
        v.setClosedAt(LocalDateTime.of(2026, 2, 1, 9, 0));
        SlaService.apply(v, DEFAULTS);
        assertThat(v.getDueAt()).isNull();
        assertThat(v.getWarningAt()).isNull();
    }

    @Test
    void configuredSeveritiesMatchIgnoringCaseAndSurroundingWhitespace() {
        catalogOf(AssessmentWorkflow.defaultWorkflowBuilder()
                .vulnerabilitySlas(new ArrayList<>(List.of(new VulnerabilitySla(" high ", 10, 5)))).build());
        Vulnerability v = open(VulnerabilitySeverity.HIGH);
        slaService.refresh(v);
        assertThat(v.getDueAt()).isEqualTo(LocalDateTime.of(2026, 1, 11, 9, 0));
        assertThat(v.getWarningAt()).isEqualTo(LocalDateTime.of(2026, 1, 6, 9, 0));
    }

    @Test
    void blankUnknownAndNullSeveritiesInTheConfigAreSkipped() {
        configured(new VulnerabilitySla(null, 5, 1), new VulnerabilitySla("  ", 5, 1),
                new VulnerabilitySla("SEVERE", 5, 1), new VulnerabilitySla("HIGH", 60, 30));
        assertThat(slaService.policy().bySeverity()).containsOnlyKeys(VulnerabilitySeverity.HIGH);
    }

    @Test
    void theFirstEntryForASeverityWins() {
        SlaService.SlaPolicy policy = SlaService.SlaPolicy.of(List.of(
                new VulnerabilitySla("HIGH", 60, 30), new VulnerabilitySla("high", 10, 5)));
        Vulnerability v = open(VulnerabilitySeverity.HIGH);
        SlaService.apply(v, policy);
        assertThat(v.getDueAt()).isEqualTo(HIGH_DUE);
    }

    @Test
    void noSlasConfiguredMeansNoDueDates() {
        catalogOf(AssessmentWorkflow.defaultWorkflowBuilder().vulnerabilitySlas(null).build());
        Vulnerability v = open(VulnerabilitySeverity.HIGH);
        v.setDueAt(HIGH_DUE);
        v.setWarningAt(HIGH_WARNING);
        slaService.refresh(v);
        assertThat(v.getDueAt()).isNull();
        assertThat(v.getWarningAt()).isNull();
    }

    @Test
    void refreshAllAppliesOneWorkflowsPolicyToEveryFinding() {
        // refreshAll takes the workflow the caller already resolved, so there is no config read
        // to count.
        AssessmentWorkflow workflow = AssessmentWorkflow.defaultWorkflowBuilder()
                .vulnerabilitySlas(new ArrayList<>(List.of(
                        new VulnerabilitySla("HIGH", 60, 30), new VulnerabilitySla("CRITICAL", 30, 20))))
                .build();
        Vulnerability high = open(VulnerabilitySeverity.HIGH);
        Vulnerability critical = open(VulnerabilitySeverity.CRITICAL);

        slaService.refreshAll(List.of(high, critical), workflow);

        assertThat(high.getDueAt()).isEqualTo(HIGH_DUE);
        assertThat(critical.getDueAt()).isEqualTo(LocalDateTime.of(2026, 1, 31, 9, 0));
    }

    @Test
    void aFindingUsesItsAssessmentsWorkflowSlas() {
        AssessmentWorkflow second = TestWorkflows.secondWorkflow();
        catalogOf(AssessmentWorkflow.defaultWorkflowBuilder().build(), second);
        when(assessmentRepository.findById("a-1")).thenReturn(Optional.of(
                Assessment.builder().id("a-1").workflowId(TestWorkflows.SECOND_ID).build()));
        Vulnerability v = Vulnerability.builder().assessmentId("a-1")
                .severity(VulnerabilitySeverity.HIGH).openedAt(OPENED_2099).build();

        slaService.refresh(v);

        // Second workflow: HIGH 14 days, warning 5 days before.
        assertThat(v.getDueAt()).isEqualTo(OPENED_2099.plusDays(14));
        assertThat(v.getWarningAt()).isEqualTo(OPENED_2099.plusDays(9));
    }

    @Test
    void aFindingWhoseAssessmentHasAnUnknownWorkflowUsesDefaultWorkflow() {
        catalogOf(AssessmentWorkflow.defaultWorkflowBuilder().build(), TestWorkflows.secondWorkflow());
        when(assessmentRepository.findById("a-2")).thenReturn(Optional.of(
                Assessment.builder().id("a-2").workflowId("gone").build()));
        Vulnerability v = Vulnerability.builder().assessmentId("a-2")
                .severity(VulnerabilitySeverity.HIGH).openedAt(OPENED_2099).build();

        slaService.refresh(v);

        // Default Workflow: HIGH 60 days, warning 30 days before.
        assertThat(v.getDueAt()).isEqualTo(OPENED_2099.plusDays(60));
        assertThat(v.getWarningAt()).isEqualTo(OPENED_2099.plusDays(30));
    }

    @Test
    void aFindingWithoutAnAssessmentUsesDefaultWorkflow() {
        catalogOf(AssessmentWorkflow.defaultWorkflowBuilder().build());
        Vulnerability v = Vulnerability.builder().severity(VulnerabilitySeverity.HIGH).openedAt(OPENED_2099).build();

        slaService.refresh(v);

        assertThat(v.getDueAt()).isEqualTo(OPENED_2099.plusDays(60));
    }

    @Test
    void refreshingWithAGivenWorkflowUsesThatWorkflowWithoutLookingAnythingUp() {
        Vulnerability critical = Vulnerability.builder().assessmentId("whatever")
                .severity(VulnerabilitySeverity.CRITICAL).openedAt(OPENED_2099).build();
        Vulnerability medium = Vulnerability.builder().assessmentId("whatever")
                .severity(VulnerabilitySeverity.MEDIUM).openedAt(OPENED_2099).build();

        slaService.refreshAll(List.of(critical, medium), TestWorkflows.secondWorkflow());

        assertThat(critical.getDueAt()).isEqualTo(OPENED_2099.plusDays(7));
        assertThat(critical.getWarningAt()).isEqualTo(OPENED_2099.plusDays(4));
        // The second workflow has no MEDIUM SLA.
        assertThat(medium.getDueAt()).isNull();
        assertThat(medium.getWarningAt()).isNull();
        org.mockito.Mockito.verifyNoInteractions(workflowCatalogService, assessmentRepository);
    }
}
