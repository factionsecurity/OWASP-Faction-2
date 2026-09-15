package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.workflow.UpdateWorkflowRequest;
import com.faction.clientportal.dto.workflow.UpdateWorkflowRequest.NamedEntry;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.exception.WorkflowConflictException;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.RemediationStage;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.WorkflowRenameTask;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentWorkflowRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.repository.WorkflowRenameTaskRepository;
import com.faction.clientportal.testsupport.TestWorkflows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Editing a workflow: guarded, with an assessment status rename rewriting that workflow's assessments in
 * the same request and a vulnerability status rename rewriting its findings in the background. (When SLA
 * changes are announced is covered by {@code WorkflowEditServiceEventsTest}.)
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkflowEditServiceTest extends TestContainersConfig {

    private static final String SECOND = TestWorkflows.SECOND_ID;

    @Autowired private WorkflowEditService editService;
    @Autowired private VulnerabilityStatusRenameService renameService;
    @Autowired private AssessmentWorkflowRepository workflowRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private WorkflowRenameTaskRepository renameTaskRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        clean();
        TestWorkflows.saveSecondWorkflow(workflowRepository);
    }

    @AfterEach
    void clean() {
        renameTaskRepository.deleteAll();
        vulnerabilityRepository.deleteAll();
        assessmentRepository.deleteAll();
        workflowRepository.deleteAll();
    }

    /** The second workflow's current settings as an edit with nothing changed. */
    private static UpdateWorkflowRequest.UpdateWorkflowRequestBuilder unchanged() {
        AssessmentWorkflow second = TestWorkflows.secondWorkflow();
        List<NamedEntry> statuses = new ArrayList<>();
        second.getStatuses().forEach(s -> statuses.add(new NamedEntry(s, s)));
        List<NamedEntry> vulnerabilityStatuses = new ArrayList<>();
        second.getVulnerabilityStatuses().forEach(s -> vulnerabilityStatuses.add(new NamedEntry(s, s)));
        return UpdateWorkflowRequest.builder()
                .statuses(statuses)
                .newAssessmentStatus(second.getNewAssessmentStatus())
                .inProgressStatus(second.getInProgressStatus())
                .completedStatus(second.getCompletedStatus())
                .statusColors(new HashMap<>(second.getStatusColors()))
                .vulnerabilitySlas(new ArrayList<>(second.getVulnerabilitySlas()))
                .vulnerabilityStatuses(vulnerabilityStatuses)
                .remediationStages(new ArrayList<>(second.getRemediationStages()))
                .allowSelfPeerReview(second.isAllowSelfPeerReview());
    }

    @Test
    void renamingAnAssessmentStatusRewritesThatWorkflowsAssessmentsInTheRequest() {
        Assessment onSecond = assessment(SECOND, "Fieldwork");
        Assessment onDefault = assessment("default", "Fieldwork");
        List<NamedEntry> statuses = List.of(new NamedEntry("Draft", "Draft"), new NamedEntry("Scoping", "Scoping"),
                new NamedEntry("Fieldwork", "On Site"), new NamedEntry("Signed Off", "Signed Off"));

        AssessmentWorkflow saved = editService.update(SECOND, unchanged()
                .statuses(statuses).inProgressStatus("On Site").build());

        assertThat(saved.getStatuses()).containsExactly("Draft", "Scoping", "On Site", "Signed Off");
        assertThat(saved.getInProgressStatus()).isEqualTo("On Site");
        assertThat(assessmentRepository.findById(onSecond.getId()).orElseThrow().getStatus()).isEqualTo("On Site");
        assertThat(assessmentRepository.findById(onDefault.getId()).orElseThrow().getStatus()).isEqualTo("Fieldwork");
    }

    @Test
    void renamingAVulnerabilityStatusRewritesTheWorkflowsFindingsInTheBackground() throws InterruptedException {
        Vulnerability finding = finding(assessment(SECOND, "Draft"), "Risk Accepted");
        Vulnerability elsewhere = finding(assessment("default", "New"), "Risk Accepted");

        AssessmentWorkflow saved = editService.update(SECOND, unchanged()
                .vulnerabilityStatuses(List.of(new NamedEntry("Risk Accepted", "Accepted Risk"))).build());

        assertThat(saved.getVulnerabilityStatuses()).containsExactly("Accepted Risk");
        WorkflowRenameTask task = awaitFinished(SECOND);
        assertThat(task.getState()).isEqualTo(WorkflowRenameTask.State.DONE);
        assertThat(task.getProcessed()).isEqualTo(1);
        assertThat(vulnerabilityRepository.findById(finding.getId()).orElseThrow().getStatus()).isEqualTo("Accepted Risk");
        assertThat(vulnerabilityRepository.findById(elsewhere.getId()).orElseThrow().getStatus()).isEqualTo("Risk Accepted");
    }

    @Test
    void runningAnInterruptedRenameAgainFinishesItAndAFinishedOneIsLeftAlone() {
        Vulnerability finding = finding(assessment(SECOND, "Draft"), "Risk Accepted");
        WorkflowRenameTask interrupted = renameTaskRepository.save(
                WorkflowRenameTask.start(SECOND, "Risk Accepted", "Accepted Risk"));

        renameService.run(interrupted.getId());
        renameService.run(interrupted.getId());

        WorkflowRenameTask done = renameTaskRepository.findById(interrupted.getId()).orElseThrow();
        assertThat(done.getState()).isEqualTo(WorkflowRenameTask.State.DONE);
        assertThat(done.getProcessed()).isEqualTo(1);
        assertThat(done.getFinishedAt()).isNotNull();
        assertThat(vulnerabilityRepository.findById(finding.getId()).orElseThrow().getStatus()).isEqualTo("Accepted Risk");
    }

    @Test
    void aRefusedEditRenamesAndSavesNothing() {
        assessment(SECOND, "Scoping");
        Assessment inFieldwork = assessment(SECOND, "Fieldwork");
        List<NamedEntry> statuses = List.of(new NamedEntry("Draft", "Draft"),
                new NamedEntry("Fieldwork", "On Site"), new NamedEntry("Signed Off", "Signed Off"));

        assertThatThrownBy(() -> editService.update(SECOND, unchanged()
                .statuses(statuses).inProgressStatus("On Site").build()))
                .isInstanceOf(WorkflowConflictException.class)
                .hasMessageContaining("Scoping");

        assertThat(assessmentRepository.findById(inFieldwork.getId()).orElseThrow().getStatus()).isEqualTo("Fieldwork");
        assertThat(workflowRepository.findById(SECOND).orElseThrow().getStatuses()).contains("Scoping", "Fieldwork");
    }

    @Test
    void renamingTheWorkflowToANameAnotherHasIsRefusedIgnoringCase() {
        workflowRepository.save(AssessmentWorkflow.defaultWorkflowBuilder()
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());

        assertThatThrownBy(() -> editService.update(SECOND, unchanged().name("default workflow").build()))
                .isInstanceOfSatisfying(WorkflowConflictException.class, e -> assertThat(e.getViolations())
                        .extracting(WorkflowConflictException.Violation::kind)
                        .containsExactly(WorkflowConflictException.NAME_TAKEN));
    }

    @Test
    void aStageIdBelongingToAnotherWorkflowIsRefused() {
        workflowRepository.save(AssessmentWorkflow.defaultWorkflowBuilder()
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        List<RemediationStage> stages = List.of(new RemediationStage("second-qa", "QA"),
                new RemediationStage("development", "Development"));

        assertThatThrownBy(() -> editService.update(SECOND, unchanged().remediationStages(stages).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("development")
                .hasMessageContaining("belongs to another workflow");
    }

    @Test
    void addingANewStatusARunningRenameIsWritingFromIsRefused() {
        renameTaskRepository.save(WorkflowRenameTask.start(SECOND, "Risk Accepted", "Accepted Risk"));
        List<NamedEntry> vulnerabilityStatuses = List.of(new NamedEntry(null, "Risk Accepted"));

        assertThatThrownBy(() -> editService.update(SECOND, unchanged()
                .vulnerabilityStatuses(vulnerabilityStatuses).build()))
                .isInstanceOfSatisfying(WorkflowConflictException.class, e -> assertThat(e.getViolations())
                        .extracting(WorkflowConflictException.Violation::kind)
                        .containsExactly(WorkflowConflictException.RENAME_IN_PROGRESS));
    }

    @Test
    void anUnknownWorkflowIsNotFound() {
        assertThatThrownBy(() -> editService.update("no-such-workflow", unchanged().build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updatingInsideAnExistingTransactionIsRefused() {
        assertThatThrownBy(() -> transactionTemplate.execute(status ->
                editService.update(SECOND, unchanged().build())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private WorkflowRenameTask awaitFinished(String workflowId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<WorkflowRenameTask> tasks = renameTaskRepository.findAll().stream()
                    .filter(t -> t.getWorkflowId().equals(workflowId)).toList();
            if (!tasks.isEmpty() && tasks.get(0).getState() != WorkflowRenameTask.State.RUNNING) {
                return tasks.get(0);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("rename task did not finish within 10 seconds");
    }

    private Assessment assessment(String workflowId, String status) {
        return assessmentRepository.save(Assessment.builder()
                .name("Edit " + UUID.randomUUID()).assessmentTypeId("type-1").organizationId("org-1")
                .workflowId(workflowId).status(status).createdAt(LocalDateTime.now()).build());
    }

    private Vulnerability finding(Assessment assessment, String status) {
        return vulnerabilityRepository.save(Vulnerability.builder()
                .name("Finding " + UUID.randomUUID()).assessmentId(assessment.getId())
                .status(status).createdAt(LocalDateTime.now()).build());
    }
}
