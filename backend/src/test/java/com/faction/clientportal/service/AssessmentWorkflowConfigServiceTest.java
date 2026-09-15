package com.faction.clientportal.service;

import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.VulnerabilitySla;
import com.faction.clientportal.repository.AssessmentWorkflowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The workflow configuration is Default Workflow. Assessment statuses are the ones it configures: the
 * fixed lifecycle values of the original status enum (DRAFT … ARCHIVED) no longer exist in any data,
 * so an assessment is completed only when it is in the configured completed status.
 *
 * <p>Saving the configuration edits Default Workflow's settings and nothing that identifies it, and
 * announces an SLA change — only a real one — so open findings' stored due dates are recalculated
 * exactly when they could have moved.
 */
@ExtendWith(MockitoExtension.class)
class AssessmentWorkflowConfigServiceTest {

    @Mock private AssessmentWorkflowRepository repository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private AssessmentWorkflowConfigService service;

    /** Stubbed per test: Mockito's strict stubs fail a class-wide stub the entity test never uses. */
    private void configuredCompletedStatus(String status) {
        when(repository.findById("default")).thenReturn(Optional.of(
                AssessmentWorkflow.defaultWorkflowBuilder().completedStatus(status).build()));
    }

    private static AssessmentWorkflow withSlas(VulnerabilitySla... slas) {
        return AssessmentWorkflow.defaultWorkflowBuilder()
                .vulnerabilitySlas(new ArrayList<>(Arrays.asList(slas))).build();
    }

    private void stored(AssessmentWorkflow workflow) {
        when(repository.findById("default")).thenReturn(Optional.ofNullable(workflow));
        when(repository.save(any(AssessmentWorkflow.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void theConfiguredCompletedStatusIsCompleted() {
        configuredCompletedStatus("Completed");
        assertThat(service.isCompletedStatus("Completed")).isTrue();
    }

    @Test
    void theRetiredLifecycleValuesAreNotCompleted() {
        configuredCompletedStatus("Completed");
        for (String retired : new String[]{"COMPLETED", "APPROVED", "ARCHIVED"}) {
            assertThat(service.isCompletedStatus(retired)).as(retired).isFalse();
        }
    }

    @Test
    void anAssessmentBuiltWithoutAStatusStartsInTheDefaultNewStatus() {
        // Not "DRAFT", which no workflow defines.
        assertThat(Assessment.builder().build().getStatus()).isEqualTo("New");
    }

    @Test
    void concurrentFirstCreationLosesTheRaceButStillReturnsTheWinnersRow() {
        AssessmentWorkflow winner = AssessmentWorkflow.defaultWorkflowBuilder().build();
        when(repository.findById("default")).thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.save(any(AssessmentWorkflow.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        AssessmentWorkflow result = service.ensureDefaultWorkflow();

        assertThat(result).isSameAs(winner);
    }

    @Test
    void withNoWorkflowRowDefaultWorkflowIsCreatedWithTheDefaults() {
        stored(null);

        AssessmentWorkflow config = service.getConfig();

        assertThat(config.getId()).isEqualTo("default");
        assertThat(config.getName()).isEqualTo("Default Workflow");
        assertThat(config.isDefaultWorkflow()).isTrue();
        assertThat(config.getCompletedStatus()).isEqualTo("Completed");
        assertThat(config.getVulnerabilitySlas()).isEqualTo(AssessmentWorkflow.defaultVulnerabilitySlas());
        assertThat(config.getCreatedAt()).isNotNull();
        verify(repository).save(any(AssessmentWorkflow.class));
    }

    @Test
    void savingChangesTheSettingsButNotWhichWorkflowItIs() {
        LocalDateTime created = LocalDateTime.of(2026, 1, 1, 9, 0);
        stored(AssessmentWorkflow.defaultWorkflowBuilder().createdAt(created).updatedAt(created).build());
        AssessmentWorkflow submitted = AssessmentWorkflow.builder()
                .id("other").name("Renamed").defaultWorkflow(false).archived(true)
                .createdAt(LocalDateTime.of(2030, 1, 1, 0, 0))
                .statuses(new ArrayList<>(List.of("Open", "Done")))
                .newAssessmentStatus("Open").inProgressStatus("Open").completedStatus("Done")
                .allowSelfPeerReview(true)
                .build();

        service.updateConfig(submitted);

        ArgumentCaptor<AssessmentWorkflow> saved = ArgumentCaptor.forClass(AssessmentWorkflow.class);
        verify(repository).save(saved.capture());
        AssessmentWorkflow workflow = saved.getValue();
        assertThat(workflow.getId()).isEqualTo("default");
        assertThat(workflow.getName()).isEqualTo("Default Workflow");
        assertThat(workflow.isDefaultWorkflow()).isTrue();
        assertThat(workflow.isArchived()).isFalse();
        assertThat(workflow.getCreatedAt()).isEqualTo(created);
        assertThat(workflow.getUpdatedAt()).isAfter(created);
        assertThat(workflow.getStatuses()).containsExactly("Open", "Done");
        assertThat(workflow.getNewAssessmentStatus()).isEqualTo("Open");
        assertThat(workflow.getInProgressStatus()).isEqualTo("Open");
        assertThat(workflow.getCompletedStatus()).isEqualTo("Done");
        assertThat(workflow.isAllowSelfPeerReview()).isTrue();
    }

    @Test
    void everySubmittedSettingIsCopiedOntoTheSavedWorkflow() {
        stored(AssessmentWorkflow.defaultWorkflowBuilder().build());
        AssessmentWorkflow submitted = AssessmentWorkflow.builder()
                .statuses(new ArrayList<>(List.of("Open", "Active", "Done")))
                .newAssessmentStatus("Open")
                .inProgressStatus("Active")
                .completedStatus("Done")
                .statusColors(java.util.Map.of("Done", "#00ff00"))
                .vulnerabilitySlas(new ArrayList<>(List.of(new VulnerabilitySla("HIGH", 14, 5))))
                .vulnerabilityStatuses(new ArrayList<>(List.of("Risk Accepted")))
                .remediationStages(new ArrayList<>(List.of(
                        new com.faction.clientportal.model.RemediationStage("qa", "QA"))))
                .allowSelfPeerReview(true)
                .build();

        service.updateConfig(submitted);

        ArgumentCaptor<AssessmentWorkflow> saved = ArgumentCaptor.forClass(AssessmentWorkflow.class);
        verify(repository).save(saved.capture());
        AssessmentWorkflow workflow = saved.getValue();
        assertThat(workflow.getStatuses()).containsExactly("Open", "Active", "Done");
        assertThat(workflow.getNewAssessmentStatus()).isEqualTo("Open");
        assertThat(workflow.getInProgressStatus()).isEqualTo("Active");
        assertThat(workflow.getCompletedStatus()).isEqualTo("Done");
        assertThat(workflow.getStatusColors()).containsEntry("Done", "#00ff00");
        assertThat(workflow.getVulnerabilitySlas()).containsExactly(new VulnerabilitySla("HIGH", 14, 5));
        assertThat(workflow.getVulnerabilityStatuses()).containsExactly("Risk Accepted");
        assertThat(workflow.getRemediationStages())
                .containsExactly(new com.faction.clientportal.model.RemediationStage("qa", "QA"));
        assertThat(workflow.isAllowSelfPeerReview()).isTrue();
    }

    @Test
    void changingAnSlaAnnouncesIt() {
        stored(withSlas(new VulnerabilitySla("HIGH", 30, 20)));

        service.updateConfig(withSlas(new VulnerabilitySla("HIGH", 60, 30)));

        verify(eventPublisher).publishEvent(any(SlaConfigChangedEvent.class));
    }

    @Test
    void removingASeverityAnnouncesIt() {
        stored(withSlas(new VulnerabilitySla("HIGH", 60, 30), new VulnerabilitySla("MEDIUM", 365, 300)));

        service.updateConfig(withSlas(new VulnerabilitySla("HIGH", 60, 30)));

        verify(eventPublisher).publishEvent(any(SlaConfigChangedEvent.class));
    }

    @Test
    void savingOtherSettingsWithTheSameSlasAnnouncesNothing() {
        stored(withSlas(new VulnerabilitySla("HIGH", 60, 30)));
        AssessmentWorkflow edited = withSlas(new VulnerabilitySla("HIGH", 60, 30));
        edited.setCompletedStatus("Done");
        edited.setStatuses(new ArrayList<>(List.of("New", "Testing", "Done")));

        service.updateConfig(edited);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void reorderedOrDifferentlySpelledButEquivalentSlasAnnounceNothing() {
        stored(withSlas(new VulnerabilitySla("CRITICAL", 30, 20), new VulnerabilitySla("HIGH", 60, 30)));

        service.updateConfig(withSlas(new VulnerabilitySla(" high ", 60, 30), new VulnerabilitySla("critical", 30, 20)));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void theFirstSaveWithTheDefaultSlasAnnouncesNothing() {
        // No row yet: getConfig() would have seeded the defaults, so saving them changes no due date.
        stored(null);

        service.updateConfig(AssessmentWorkflow.defaultWorkflowBuilder().build());

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void anSlaChangeNamesDefaultWorkflow() {
        stored(withSlas(new VulnerabilitySla("HIGH", 30, 20)));

        service.updateConfig(withSlas(new VulnerabilitySla("HIGH", 60, 30)));

        ArgumentCaptor<SlaConfigChangedEvent> event = ArgumentCaptor.forClass(SlaConfigChangedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().workflowId()).isEqualTo("default");
    }
}
