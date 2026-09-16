package com.faction.clientportal.service;

import com.faction.clientportal.dto.workflow.UpdateWorkflowRequest;
import com.faction.clientportal.dto.workflow.UpdateWorkflowRequest.NamedEntry;
import com.faction.clientportal.exception.WorkflowConflictException;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentWorkflowRepository;
import com.faction.clientportal.repository.WorkflowRenameTaskRepository;
import com.faction.clientportal.testsupport.TestWorkflows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** An edit announces an SLA change for its own workflow, only after the settings are saved, and only when the SLAs changed. */
@ExtendWith(MockitoExtension.class)
class WorkflowEditServiceEventsTest {

    private static final String SECOND = TestWorkflows.SECOND_ID;

    @Mock private AssessmentWorkflowRepository workflowRepository;
    @Mock private AssessmentWorkflowConfigService workflowConfigService;
    @Mock private WorkflowSettingsGuard settingsGuard;
    @Mock private AssessmentRepository assessmentRepository;
    @Mock private WorkflowRenameTaskRepository renameTaskRepository;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private WorkflowEditService editService;

    private final AssessmentWorkflow second = TestWorkflows.secondWorkflow();

    @BeforeEach
    void setUp() {
        when(workflowRepository.findById(SECOND)).thenReturn(Optional.of(second));
        when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
    }

    private UpdateWorkflowRequest unchanged() {
        List<NamedEntry> statuses = new ArrayList<>();
        second.getStatuses().forEach(s -> statuses.add(new NamedEntry(s, s)));
        List<NamedEntry> vulnerabilityStatuses = new ArrayList<>();
        second.getVulnerabilityStatuses().forEach(s -> vulnerabilityStatuses.add(new NamedEntry(s, s)));
        return UpdateWorkflowRequest.builder()
                .statuses(statuses)
                .newAssessmentStatus(second.getNewAssessmentStatus())
                .inProgressStatus(second.getInProgressStatus())
                .completedStatus(second.getCompletedStatus())
                .vulnerabilitySlas(new ArrayList<>(second.getVulnerabilitySlas()))
                .vulnerabilityStatuses(vulnerabilityStatuses)
                .remediationStages(new ArrayList<>(second.getRemediationStages()))
                .build();
    }

    @Test
    void anSlaChangeIsAnnouncedForThatWorkflowAfterTheSave() {
        when(workflowConfigService.saveSettings(any(), any()))
                .thenReturn(new AssessmentWorkflowConfigService.SettingsSave(second, true));

        editService.update(SECOND, unchanged());

        InOrder order = inOrder(workflowConfigService, eventPublisher);
        order.verify(workflowConfigService).saveSettings(any(), any());
        order.verify(eventPublisher).publishEvent(new SlaConfigChangedEvent(SECOND));
    }

    @Test
    void anEditThatKeepsTheSlasAnnouncesNothing() {
        when(workflowConfigService.saveSettings(any(), any()))
                .thenReturn(new AssessmentWorkflowConfigService.SettingsSave(second, false));

        editService.update(SECOND, unchanged());

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void aRefusedEditSavesAndAnnouncesNothing() {
        doThrow(new WorkflowConflictException(new WorkflowConflictException.Violation(
                WorkflowConflictException.ASSESSMENT_STATUS_IN_USE, "Fieldwork", 1)))
                .when(settingsGuard).check(any(), any());

        assertThatThrownBy(() -> editService.update(SECOND, unchanged()))
                .isInstanceOf(WorkflowConflictException.class);

        verify(workflowConfigService, never()).saveSettings(any(), any());
        verifyNoInteractions(eventPublisher);
    }
}
