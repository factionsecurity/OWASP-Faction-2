package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.exception.WorkflowConflictException;
import com.faction.clientportal.exception.WorkflowConflictException.Violation;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilityStageCompletion;
import com.faction.clientportal.model.WorkflowRenameTask;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.repository.VulnerabilityStageCompletionRepository;
import com.faction.clientportal.repository.WorkflowRenameTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Removing a status, vulnerability status or stage is refused while something on the workflow is still
 * in it, and a vulnerability status a running rename is writing cannot be changed. Every refusal names
 * what is in use and how much, so the editor can say why.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkflowSettingsGuardTest extends TestContainersConfig {

    private static final String SECOND = "second-workflow";

    @Autowired private WorkflowSettingsGuard guard;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private VulnerabilityStageCompletionRepository stageCompletionRepository;
    @Autowired private WorkflowRenameTaskRepository renameTaskRepository;

    @BeforeEach
    void clean() {
        renameTaskRepository.deleteAll();
        stageCompletionRepository.deleteAll();
        vulnerabilityRepository.deleteAll();
        assessmentRepository.deleteAll();
    }

    @AfterEach
    void cleanUp() {
        clean();
    }

    private static WorkflowSettingsChange removing(Set<String> statuses, Set<String> vulnerabilityStatuses,
                                                   Map<String, String> stages) {
        return new WorkflowSettingsChange(Map.of(), statuses, Map.of(), vulnerabilityStatuses, stages);
    }

    @Test
    void removingThingsNothingUsesIsAllowed() {
        assessment(SECOND, "Draft");

        assertThatCode(() -> guard.check(SECOND,
                removing(Set.of("Scoping"), Set.of("Risk Accepted"), Map.of("second-qa", "QA"))))
                .doesNotThrowAnyException();
    }

    @Test
    void everyRemovalStillInUseIsRefusedTogetherWithItsCount() {
        Assessment one = assessment(SECOND, "Fieldwork");
        assessment(SECOND, "Fieldwork");
        Vulnerability finding = finding(one, "Risk Accepted");
        completion(finding, "second-qa");

        assertThatThrownBy(() -> guard.check(SECOND,
                removing(Set.of("Fieldwork"), Set.of("Risk Accepted"), Map.of("second-qa", "QA"))))
                .isInstanceOfSatisfying(WorkflowConflictException.class, e -> org.assertj.core.api.Assertions
                        .assertThat(e.getViolations()).containsExactlyInAnyOrder(
                                new Violation(WorkflowConflictException.ASSESSMENT_STATUS_IN_USE, "Fieldwork", 2),
                                new Violation(WorkflowConflictException.VULNERABILITY_STATUS_IN_USE, "Risk Accepted", 1),
                                new Violation(WorkflowConflictException.REMEDIATION_STAGE_IN_USE, "QA", 1)))
                .hasMessageContaining("Fieldwork");
    }

    @Test
    void anotherWorkflowsUseDoesNotBlockTheRemoval() {
        assessment("default", "Fieldwork");

        assertThatCode(() -> guard.check(SECOND, removing(Set.of("Fieldwork"), Set.of(), Map.of())))
                .doesNotThrowAnyException();
    }

    @Test
    void aVulnerabilityStatusARunningRenameIsWritingCannotBeChanged() {
        renameTaskRepository.save(WorkflowRenameTask.start(SECOND, "Risk Accepted", "Accepted Risk"));
        WorkflowSettingsChange renameAgain = new WorkflowSettingsChange(
                Map.of(), Set.of(), Map.of("Accepted Risk", "Risk Taken"), Set.of(), Map.of());

        assertThatThrownBy(() -> guard.check(SECOND, renameAgain))
                .isInstanceOfSatisfying(WorkflowConflictException.class, e -> org.assertj.core.api.Assertions
                        .assertThat(e.getViolations()).containsExactly(
                                new Violation(WorkflowConflictException.RENAME_IN_PROGRESS, "Accepted Risk", 0)));
    }

    private Assessment assessment(String workflowId, String status) {
        return assessmentRepository.save(Assessment.builder()
                .name("Guard " + UUID.randomUUID()).assessmentTypeId("type-1").organizationId("org-1")
                .workflowId(workflowId).status(status).createdAt(LocalDateTime.now()).build());
    }

    private Vulnerability finding(Assessment assessment, String status) {
        return vulnerabilityRepository.save(Vulnerability.builder()
                .name("Finding " + UUID.randomUUID()).assessmentId(assessment.getId())
                .status(status).createdAt(LocalDateTime.now()).build());
    }

    private void completion(Vulnerability finding, String stageId) {
        stageCompletionRepository.save(VulnerabilityStageCompletion.builder()
                .id(UUID.randomUUID().toString()).vulnerabilityId(finding.getId())
                .stageId(stageId).completedAt(LocalDateTime.now()).build());
    }
}
