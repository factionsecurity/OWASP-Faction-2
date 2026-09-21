package com.faction.clientportal.service;

import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.VulnerabilityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The SLA-change listener runs the recalculation unless configuration switches it off. */
@ExtendWith(MockitoExtension.class)
class SlaRecalculationServiceListenerTest {

    @Mock private VulnerabilityRepository vulnerabilityRepository;
    @Mock private SlaService slaService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private WorkflowCatalogService workflowCatalogService;

    @Test
    void switchedOffByConfigurationItDoesNothing() {
        new SlaRecalculationService(vulnerabilityRepository, slaService, workflowCatalogService, transactionTemplate, 5000, false)
                .onSlaConfigChanged(new SlaConfigChangedEvent("default"));

        verifyNoInteractions(vulnerabilityRepository, slaService, transactionTemplate, workflowCatalogService);
    }

    @Test
    void switchedOnItRecalculates() {
        when(workflowCatalogService.load()).thenReturn(WorkflowCatalog.of(List.of(
                AssessmentWorkflow.defaultWorkflowBuilder().build())));
        when(transactionTemplate.execute(any())).thenReturn(null);

        new SlaRecalculationService(vulnerabilityRepository, slaService, workflowCatalogService, transactionTemplate, 5000, true)
                .onSlaConfigChanged(new SlaConfigChangedEvent("default"));

        verify(transactionTemplate).execute(any());
    }

    // ── Batch retry (finding 2) ─────────────────────────────────────────────

    @Test
    void transientFailureOnFirstAttemptThenSuccessStillProcessesTheBatch() {
        Vulnerability v = Vulnerability.builder()
                .id("v1")
                .assessmentId("assessment-1")
                .severity(VulnerabilitySeverity.HIGH)
                .status("Open")
                .openedAt(LocalDateTime.now())
                .build();
        when(workflowCatalogService.load()).thenReturn(WorkflowCatalog.of(List.of(
                AssessmentWorkflow.defaultWorkflowBuilder().build())));
        when(vulnerabilityRepository.findOpenAfterId(eq(""), any())).thenReturn(List.of(v));
        // Simulate SlaService actually changing the dates, so the batch outcome reflects real work.
        doAnswer(invocation -> {
            List<Vulnerability> batch = invocation.getArgument(0);
            for (Vulnerability vuln : batch) {
                vuln.setDueAt(LocalDateTime.now().plusDays(10));
            }
            return null;
        }).when(slaService).refreshAll(anyList(), any());

        AtomicInteger calls = new AtomicInteger();
        when(transactionTemplate.execute(ArgumentMatchers.<TransactionCallback<Object>>any()))
                .thenAnswer(invocation -> {
                    if (calls.getAndIncrement() == 0) {
                        throw new CannotAcquireLockException("could not acquire lock");
                    }
                    TransactionCallback<?> callback = invocation.getArgument(0);
                    return callback.doInTransaction(null);
                });

        SlaRecalculationService service = new SlaRecalculationService(
                vulnerabilityRepository, slaService, workflowCatalogService, transactionTemplate, 5000, true);

        SlaRecalculationService.RecalculationResult result = service.recalculateOpenFindings();

        assertThat(calls.get()).isEqualTo(2);
        assertThat(result).isEqualTo(new SlaRecalculationService.RecalculationResult(1, 0));
        verify(vulnerabilityRepository).saveAll(anyList());
    }

    @Test
    void nonTransientExceptionIsNotRetried() {
        when(workflowCatalogService.load()).thenReturn(WorkflowCatalog.of(List.of(
                AssessmentWorkflow.defaultWorkflowBuilder().build())));
        when(transactionTemplate.execute(ArgumentMatchers.<TransactionCallback<Object>>any()))
                .thenThrow(new IllegalStateException("boom"));

        SlaRecalculationService service = new SlaRecalculationService(
                vulnerabilityRepository, slaService, workflowCatalogService, transactionTemplate, 5000, true);

        assertThatThrownBy(service::recalculateOpenFindings)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        verify(transactionTemplate, times(1)).execute(any());
        verify(vulnerabilityRepository, never()).findOpenOutsideWorkflowsAfterId(any(), any(), any());
    }

    @Test
    void transientFailureExhaustingAllAttemptsRethrowsAndStopsRetrying() {
        when(workflowCatalogService.load()).thenReturn(WorkflowCatalog.of(List.of(
                AssessmentWorkflow.defaultWorkflowBuilder().build())));
        when(transactionTemplate.execute(ArgumentMatchers.<TransactionCallback<Object>>any()))
                .thenThrow(new CannotAcquireLockException("could not acquire lock"));

        SlaRecalculationService service = new SlaRecalculationService(
                vulnerabilityRepository, slaService, workflowCatalogService, transactionTemplate, 5000, true);

        assertThatThrownBy(service::recalculateOpenFindings)
                .isInstanceOf(CannotAcquireLockException.class);

        // MAX_BATCH_ATTEMPTS total attempts, then give up.
        verify(transactionTemplate, times(SlaRecalculationService.MAX_BATCH_ATTEMPTS)).execute(any());
    }

    // ── Admin trigger ───────────────────────────────────────────────────────

    @Test
    void theAdminTriggerRecalculatesEvenWhenTheConfigChangeSwitchIsOff() {
        when(workflowCatalogService.load()).thenReturn(WorkflowCatalog.of(List.of(
                AssessmentWorkflow.defaultWorkflowBuilder().build())));
        when(transactionTemplate.execute(any())).thenReturn(null);

        new SlaRecalculationService(vulnerabilityRepository, slaService, workflowCatalogService, transactionTemplate, 5000, false)
                .recalculateInBackground();

        verify(transactionTemplate).execute(any());
    }

    @Test
    void theAdminTriggerLogsAFailedRunInsteadOfThrowing() {
        when(workflowCatalogService.load()).thenReturn(WorkflowCatalog.of(List.of(
                AssessmentWorkflow.defaultWorkflowBuilder().build())));
        when(transactionTemplate.execute(ArgumentMatchers.<TransactionCallback<Object>>any()))
                .thenThrow(new IllegalStateException("boom"));

        SlaRecalculationService service = new SlaRecalculationService(
                vulnerabilityRepository, slaService, workflowCatalogService, transactionTemplate, 5000, true);

        assertThatCode(service::recalculateInBackground).doesNotThrowAnyException();
    }

    @Test
    void anSlaChangeOnAWorkflowRecalculatesThatWorkflowOnly() {
        when(workflowCatalogService.load()).thenReturn(WorkflowCatalog.of(List.of(
                AssessmentWorkflow.defaultWorkflowBuilder().build(),
                com.faction.clientportal.testsupport.TestWorkflows.secondWorkflow())));
        when(transactionTemplate.execute(ArgumentMatchers.<TransactionCallback<Object>>any()))
                .thenAnswer(invocation -> ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));

        new SlaRecalculationService(vulnerabilityRepository, slaService, workflowCatalogService, transactionTemplate, 5000, true)
                .onSlaConfigChanged(new SlaConfigChangedEvent(com.faction.clientportal.testsupport.TestWorkflows.SECOND_ID));

        verify(vulnerabilityRepository).findOpenInWorkflowAfterId(eq(""), eq(com.faction.clientportal.testsupport.TestWorkflows.SECOND_ID), any());
        verify(vulnerabilityRepository, never()).findOpenOutsideWorkflowsAfterId(any(), any(), any());
    }

    @Test
    void defaultWorkflowsRecalculationExcludesTheOtherWorkflows() {
        when(workflowCatalogService.load()).thenReturn(WorkflowCatalog.of(List.of(
                AssessmentWorkflow.defaultWorkflowBuilder().build(),
                com.faction.clientportal.testsupport.TestWorkflows.secondWorkflow())));
        when(transactionTemplate.execute(ArgumentMatchers.<TransactionCallback<Object>>any()))
                .thenAnswer(invocation -> ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));

        new SlaRecalculationService(vulnerabilityRepository, slaService, workflowCatalogService, transactionTemplate, 5000, true)
                .recalculateOpenFindings("default");

        verify(vulnerabilityRepository).findOpenOutsideWorkflowsAfterId(
                eq(""), eq(List.of(com.faction.clientportal.testsupport.TestWorkflows.SECOND_ID)), any());
    }
}
