package com.faction.clientportal.service;

import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentWorkflowConfig;
import com.faction.clientportal.repository.AssessmentWorkflowConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Assessment statuses are the ones the workflow configures. The fixed lifecycle values of the
 * original status enum (DRAFT … ARCHIVED) no longer exist in any data, so nothing may treat them as
 * meaningful: an assessment is completed only when it is in the configured completed status.
 */
@ExtendWith(MockitoExtension.class)
class AssessmentWorkflowConfigServiceTest {

    @Mock private AssessmentWorkflowConfigRepository repository;
    @InjectMocks private AssessmentWorkflowConfigService service;

    /** Stubbed per test: Mockito's strict stubs fail a class-wide stub the entity test never uses. */
    private void configuredCompletedStatus(String status) {
        when(repository.findById("singleton")).thenReturn(Optional.of(
                AssessmentWorkflowConfig.builder().id("singleton").completedStatus(status).build()));
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
}
