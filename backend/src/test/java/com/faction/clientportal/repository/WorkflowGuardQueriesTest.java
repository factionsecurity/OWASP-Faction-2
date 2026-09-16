package com.faction.clientportal.repository;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentType;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilityStageCompletion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The queries behind the workflow guards (is a status or stage still in use on this workflow?), the
 * renames (rewrite a status on one workflow only) and the admin usage counts.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkflowGuardQueriesTest extends TestContainersConfig {

    private static final String SECOND = "second-workflow";

    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private AssessmentTypeRepository assessmentTypeRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private VulnerabilityStageCompletionRepository stageCompletionRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    @BeforeEach
    void clean() {
        stageCompletionRepository.deleteAll();
        vulnerabilityRepository.deleteAll();
        assessmentRepository.deleteAll();
        assessmentTypeRepository.deleteAll();
    }

    @Test
    void countsNonDeletedAssessmentsInAStatusOnOneWorkflow() {
        assessment(SECOND, "Fieldwork", null);
        assessment(SECOND, "Fieldwork", null);
        assessment(SECOND, "Fieldwork", LocalDateTime.now());   // deleted
        assessment(SECOND, "Draft", null);
        assessment("default", "Fieldwork", null);                // other workflow

        assertThat(assessmentRepository.countByWorkflowIdAndStatusAndDeletedAtIsNull(SECOND, "Fieldwork")).isEqualTo(2);
        assertThat(assessmentRepository.countByWorkflowIdAndStatusAndDeletedAtIsNull(SECOND, "Signed Off")).isZero();
        assertThat(assessmentRepository.countByWorkflowIdAndDeletedAtIsNull(SECOND)).isEqualTo(3);
    }

    @Test
    void groupsNonDeletedAssessmentsAndTypesByWorkflow() {
        assessment(SECOND, "Draft", null);
        assessment(SECOND, "Draft", LocalDateTime.now());        // deleted
        assessment("default", "New", null);
        assessment("default", "New", null);
        type("Web", SECOND);
        type("PCI", SECOND);
        type("Mobile", "default");

        assertThat(asMap(assessmentRepository.countGroupedByWorkflowId()))
                .containsExactlyInAnyOrderEntriesOf(Map.of(SECOND, 1L, "default", 2L));
        assertThat(asMap(assessmentTypeRepository.countGroupedByWorkflowId()))
                .containsExactlyInAnyOrderEntriesOf(Map.of(SECOND, 2L, "default", 1L));
        assertThat(assessmentTypeRepository.countByWorkflowId(SECOND)).isEqualTo(2);
    }

    @Test
    void renamesAnAssessmentStatusOnOneWorkflowOnlyIncludingDeletedRows() {
        Assessment live = assessment(SECOND, "Fieldwork", null);
        Assessment deleted = assessment(SECOND, "Fieldwork", LocalDateTime.now());
        Assessment otherWorkflow = assessment("default", "Fieldwork", null);

        Integer updated = transactionTemplate.execute(s ->
                assessmentRepository.renameStatusInWorkflow(SECOND, "Fieldwork", "Testing On Site"));

        assertThat(updated).isEqualTo(2);
        assertThat(assessmentRepository.findById(live.getId()).orElseThrow().getStatus()).isEqualTo("Testing On Site");
        assertThat(assessmentRepository.findById(deleted.getId()).orElseThrow().getStatus()).isEqualTo("Testing On Site");
        assertThat(assessmentRepository.findById(otherWorkflow.getId()).orElseThrow().getStatus()).isEqualTo("Fieldwork");
    }

    @Test
    void countsNonDeletedFindingsInAStatusOnOneWorkflow() {
        Assessment second = assessment(SECOND, "Draft", null);
        Assessment other = assessment("default", "New", null);
        finding(second, "Risk Accepted", null);
        finding(second, "Risk Accepted", null);
        finding(second, "Risk Accepted", LocalDateTime.now());   // deleted
        finding(other, "Risk Accepted", null);                   // other workflow

        assertThat(vulnerabilityRepository.countInWorkflowWithStatus(SECOND, "Risk Accepted")).isEqualTo(2);
        assertThat(vulnerabilityRepository.countInWorkflowWithStatus(SECOND, "Deferred")).isZero();
    }

    @Test
    void renamesFindingStatusesInBatchesOnOneWorkflowOnly() {
        Assessment second = assessment(SECOND, "Draft", null);
        Assessment other = assessment("default", "New", null);
        for (int i = 0; i < 5; i++) {
            finding(second, "Risk Accepted", null);
        }
        Vulnerability deleted = finding(second, "Risk Accepted", LocalDateTime.now());
        Vulnerability untouched = finding(other, "Risk Accepted", null);

        Integer first = transactionTemplate.execute(s ->
                vulnerabilityRepository.renameStatusInWorkflowBatch(SECOND, "Risk Accepted", "Accepted Risk", 4));
        Integer rest = transactionTemplate.execute(s ->
                vulnerabilityRepository.renameStatusInWorkflowBatch(SECOND, "Risk Accepted", "Accepted Risk", 4));
        Integer none = transactionTemplate.execute(s ->
                vulnerabilityRepository.renameStatusInWorkflowBatch(SECOND, "Risk Accepted", "Accepted Risk", 4));

        assertThat(first).isEqualTo(4);
        assertThat(rest).isEqualTo(2);
        assertThat(none).isZero();
        assertThat(vulnerabilityRepository.findById(deleted.getId()).orElseThrow().getStatus()).isEqualTo("Accepted Risk");
        assertThat(vulnerabilityRepository.findById(untouched.getId()).orElseThrow().getStatus()).isEqualTo("Risk Accepted");
    }

    @Test
    void countsStageCompletionsByStageId() {
        Assessment second = assessment(SECOND, "Draft", null);
        Vulnerability a = finding(second, "Open", null);
        Vulnerability b = finding(second, "Open", null);
        completion(a, "second-qa");
        completion(b, "second-qa");
        completion(a, "second-live");

        assertThat(stageCompletionRepository.countByStageId("second-qa")).isEqualTo(2);
        assertThat(stageCompletionRepository.countByStageId("development")).isZero();
    }

    private Assessment assessment(String workflowId, String status, LocalDateTime deletedAt) {
        return assessmentRepository.save(Assessment.builder()
                .name("Guard " + UUID.randomUUID())
                .assessmentTypeId("type-1")
                .organizationId("org-1")
                .workflowId(workflowId)
                .status(status)
                .deletedAt(deletedAt)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private Vulnerability finding(Assessment assessment, String status, LocalDateTime deletedAt) {
        // Never opened, so the every-minute past-due job leaves it alone.
        return vulnerabilityRepository.save(Vulnerability.builder()
                .name("Finding " + UUID.randomUUID())
                .assessmentId(assessment.getId())
                .status(status)
                .deletedAt(deletedAt)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private void type(String name, String workflowId) {
        assessmentTypeRepository.save(AssessmentType.builder()
                .name(name + " " + UUID.randomUUID())
                .description(name)
                .workflowId(workflowId)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private void completion(Vulnerability finding, String stageId) {
        stageCompletionRepository.save(VulnerabilityStageCompletion.builder()
                .id(UUID.randomUUID().toString())
                .vulnerabilityId(finding.getId())
                .stageId(stageId)
                .completedAt(LocalDateTime.now())
                .build());
    }

    private static Map<String, Long> asMap(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(r -> (String) r[0], r -> ((Number) r[1]).longValue()));
    }
}
