package com.faction.clientportal.service;

import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.testsupport.TestWorkflows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which finder a recalculation run pages through. With only Default Workflow every open finding is
 * Default Workflow's, so the run skips the per-row "not on another workflow" subquery.
 */
@ExtendWith(MockitoExtension.class)
class SlaRecalculationFinderTest {

    @Mock private VulnerabilityRepository vulnerabilityRepository;
    @Mock private SlaService slaService;
    @Mock private WorkflowCatalogService workflowCatalogService;
    @Mock private TransactionTemplate transactionTemplate;

    private final AssessmentWorkflow defaults = AssessmentWorkflow.defaultWorkflowBuilder().build();
    private SlaRecalculationService service;

    @BeforeEach
    void setUp() {
        when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        service = new SlaRecalculationService(
                vulnerabilityRepository, slaService, workflowCatalogService, transactionTemplate, 500, false);
    }

    @Test
    void onlyDefaultWorkflow_pagesThroughEveryOpenFindingWithoutTheWorkflowSubquery() {
        when(workflowCatalogService.load()).thenReturn(WorkflowCatalog.of(List.of(defaults)));
        Vulnerability open = Vulnerability.builder().id("v-1").severity(VulnerabilitySeverity.HIGH)
                .openedAt(LocalDateTime.of(2099, 1, 1, 0, 0)).build();
        when(vulnerabilityRepository.findOpenAfterId(eq(""), any())).thenReturn(List.of(open));

        service.recalculateOpenFindings(AssessmentWorkflow.DEFAULT_ID);

        verify(vulnerabilityRepository).findOpenAfterId(eq(""), any());
        verify(vulnerabilityRepository, never()).findOpenOutsideWorkflowsAfterId(any(), any(), any());
        verify(vulnerabilityRepository, never()).findOpenInWorkflowAfterId(any(), any(), any());
        verify(slaService).refreshAll(List.of(open), defaults);
    }

    @Test
    void defaultWorkflowAlongsideAnother_stillExcludesTheOthersFindings() {
        when(workflowCatalogService.load())
                .thenReturn(WorkflowCatalog.of(List.of(defaults, TestWorkflows.secondWorkflow())));
        when(vulnerabilityRepository.findOpenOutsideWorkflowsAfterId(eq(""), eq(List.of(TestWorkflows.SECOND_ID)), any()))
                .thenReturn(List.of());

        service.recalculateOpenFindings(AssessmentWorkflow.DEFAULT_ID);

        verify(vulnerabilityRepository).findOpenOutsideWorkflowsAfterId(eq(""), eq(List.of(TestWorkflows.SECOND_ID)), any());
        verify(vulnerabilityRepository, never()).findOpenAfterId(any(), any());
    }
}
