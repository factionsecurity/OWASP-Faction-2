package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentWorkflowRepository;
import com.faction.clientportal.testsupport.TestWorkflows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A finding's stored SLA dates come from its own assessment's workflow, read from the database. */
@SpringBootTest
@ActiveProfiles("test")
class SlaPerWorkflowTest extends TestContainersConfig {

    private static final LocalDateTime OPENED = LocalDateTime.of(2099, 1, 1, 9, 0);

    @Autowired private SlaService slaService;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private AssessmentWorkflowRepository workflowRepository;

    private String assessmentId;

    @AfterEach
    void tearDown() {
        if (assessmentId != null) {
            assessmentRepository.deleteById(assessmentId);
        }
        workflowRepository.deleteAll();
    }

    @Test
    void findingsOnTheSecondWorkflowGetItsSlas() {
        TestWorkflows.saveSecondWorkflow(workflowRepository);
        assessmentId = assessmentRepository.save(Assessment.builder()
                .name("Second workflow " + UUID.randomUUID())
                .workflowId(TestWorkflows.SECOND_ID).build()).getId();
        Vulnerability low = Vulnerability.builder().assessmentId(assessmentId)
                .severity(VulnerabilitySeverity.LOW).openedAt(OPENED).build();

        slaService.refresh(low);

        // Second workflow LOW 90/30; Default Workflow has no LOW SLA at all.
        assertThat(low.getDueAt()).isEqualTo(OPENED.plusDays(90));
        assertThat(low.getWarningAt()).isEqualTo(OPENED.plusDays(60));
    }

    @Test
    void workflowIdsAreReadForManyAssessments() {
        String first = assessmentRepository.save(Assessment.builder()
                .name("Ids " + UUID.randomUUID()).workflowId(TestWorkflows.SECOND_ID).build()).getId();
        String second = assessmentRepository.save(Assessment.builder()
                .name("Ids " + UUID.randomUUID()).build()).getId();
        try {
            java.util.Map<Object, Object> byId = new java.util.HashMap<>();
            for (Object[] row : assessmentRepository.findWorkflowIdsByIds(java.util.List.of(first, second, "missing"))) {
                byId.put(row[0], row[1]);
            }
            assertThat(byId).containsOnly(
                    java.util.Map.entry(first, TestWorkflows.SECOND_ID),
                    java.util.Map.entry(second, "default"));
        } finally {
            assessmentRepository.deleteAllById(java.util.List.of(first, second));
        }
    }
}
