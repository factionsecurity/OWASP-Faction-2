package com.faction.clientportal.service;

import com.faction.clientportal.edition.EditionPolicy;
import com.faction.clientportal.exception.WorkflowConflictException;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.repository.VulnerabilityStageCompletionRepository;
import com.faction.clientportal.testsupport.TestWorkflows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link AssessmentWorkflowMoveService#checkMove} validates a target workflow without loading or mapping
 * any finding or stage completion — it is meant to be cheap enough to call purely to check whether a move
 * would be refused (see {@code AssessmentService#workflowToMoveTo}).
 */
@ExtendWith(MockitoExtension.class)
class AssessmentWorkflowMoveServiceTest {

    @Mock private AssessmentRepository assessmentRepository;
    @Mock private VulnerabilityRepository vulnerabilityRepository;
    @Mock private VulnerabilityStageCompletionRepository stageCompletionRepository;
    @Mock private WorkflowCatalogService workflowCatalogService;
    @Mock private WorkflowMappingService mappingService;
    @Mock private EditionPolicy editionPolicy;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks
    private AssessmentWorkflowMoveService moveService;

    private final AssessmentWorkflow defaults = AssessmentWorkflow.defaultWorkflowBuilder().build();

    @Test
    void checkMoveRefusesAnUnknownTargetWithoutTouchingFindingsOrCompletions() {
        Assessment assessment = Assessment.builder().id("a1").workflowId("default").build();
        WorkflowCatalog catalog = WorkflowCatalog.of(List.of(defaults));

        assertThatThrownBy(() -> moveService.checkMove(assessment, "no-such-workflow", catalog))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(vulnerabilityRepository, stageCompletionRepository);
    }

    @Test
    void checkMoveRefusesAnArchivedTargetWithoutTouchingFindingsOrCompletions() {
        AssessmentWorkflow archived = TestWorkflows.secondWorkflow();
        archived.setArchived(true);
        Assessment assessment = Assessment.builder().id("a1").workflowId("default").build();
        WorkflowCatalog catalog = WorkflowCatalog.of(List.of(defaults, archived));

        assertThatThrownBy(() -> moveService.checkMove(assessment, archived.getId(), catalog))
                .isInstanceOf(WorkflowConflictException.class);

        verifyNoInteractions(vulnerabilityRepository, stageCompletionRepository);
    }
}
