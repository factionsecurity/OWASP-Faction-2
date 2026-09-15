package com.faction.clientportal.service;

import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentWorkflowConfig;
import com.faction.clientportal.model.AssessmentWorkflowConfig.VulnerabilitySla;
import com.faction.clientportal.repository.AssessmentWorkflowConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

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
 * Assessment statuses are the ones the workflow configures. The fixed lifecycle values of the
 * original status enum (DRAFT … ARCHIVED) no longer exist in any data, so nothing may treat them as
 * meaningful: an assessment is completed only when it is in the configured completed status.
 *
 * <p>Saving the config announces an SLA change, and only a real one, so open findings' stored due
 * dates are recalculated exactly when they could have moved.
 */
@ExtendWith(MockitoExtension.class)
class AssessmentWorkflowConfigServiceTest {

    @Mock private AssessmentWorkflowConfigRepository repository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private AssessmentWorkflowConfigService service;

    /** Stubbed per test: Mockito's strict stubs fail a class-wide stub the entity test never uses. */
    private void configuredCompletedStatus(String status) {
        when(repository.findById("singleton")).thenReturn(Optional.of(
                AssessmentWorkflowConfig.builder().id("singleton").completedStatus(status).build()));
    }

    private static AssessmentWorkflowConfig withSlas(VulnerabilitySla... slas) {
        return AssessmentWorkflowConfig.builder()
                .id("singleton").vulnerabilitySlas(new ArrayList<>(Arrays.asList(slas))).build();
    }

    private void stored(AssessmentWorkflowConfig config) {
        when(repository.findById("singleton")).thenReturn(Optional.ofNullable(config));
        when(repository.save(any(AssessmentWorkflowConfig.class))).thenAnswer(inv -> inv.getArgument(0));
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
        AssessmentWorkflowConfig edited = withSlas(new VulnerabilitySla("HIGH", 60, 30));
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

        service.updateConfig(AssessmentWorkflowConfig.builder().id("singleton").build());

        verifyNoInteractions(eventPublisher);
    }
}
