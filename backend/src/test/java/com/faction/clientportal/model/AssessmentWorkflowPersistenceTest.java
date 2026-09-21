package com.faction.clientportal.model;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.AssessmentWorkflowRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Workflows are rows of assessment_workflows; Default Workflow starts with exactly the settings the
 * single workflow configuration defaulted to, and every assessment type and assessment belongs to
 * Default Workflow unless told otherwise — in Java and in the database column default.
 */
@SpringBootTest
@ActiveProfiles("test")
class AssessmentWorkflowPersistenceTest extends TestContainersConfig {

    @Autowired private AssessmentWorkflowRepository workflowRepository;
    @Autowired private AssessmentTypeRepository assessmentTypeRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<String> typeIds = new ArrayList<>();
    private final List<String> assessmentIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        workflowRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        assessmentRepository.deleteAllById(assessmentIds);
        assessmentTypeRepository.deleteAllById(typeIds);
    }

    @Test
    void defaultWorkflowStartsWithTodaysDefaults() {
        workflowRepository.save(AssessmentWorkflow.defaultWorkflowBuilder().build());

        AssessmentWorkflow stored = workflowRepository.findById("default").orElseThrow();
        assertThat(stored.getName()).isEqualTo("Default Workflow");
        assertThat(stored.isDefaultWorkflow()).isTrue();
        assertThat(stored.isArchived()).isFalse();
        assertThat(stored.getStatuses()).containsExactly(
                "New", "Scheduling", "Data Gathering", "Planning", "Testing", "Reporting", "Completed", "NA");
        assertThat(stored.getNewAssessmentStatus()).isEqualTo("New");
        assertThat(stored.getInProgressStatus()).isEqualTo("Testing");
        assertThat(stored.getCompletedStatus()).isEqualTo("Completed");
        assertThat(stored.getStatusColors()).isEmpty();
        assertThat(stored.getVulnerabilitySlas()).containsExactly(
                new VulnerabilitySla("CRITICAL", 30, 20),
                new VulnerabilitySla("HIGH", 60, 30),
                new VulnerabilitySla("MEDIUM", 365, 300));
        assertThat(stored.getVulnerabilityStatuses()).isEmpty();
        assertThat(stored.getRemediationStages()).containsExactly(
                new RemediationStage("development", "Development"),
                new RemediationStage("staging", "Staging"),
                new RemediationStage("production", "Production"));
        assertThat(stored.isAllowSelfPeerReview()).isFalse();
    }

    @Test
    void everySettingRoundTrips() {
        LocalDateTime created = LocalDateTime.of(2099, 1, 1, 9, 0);
        workflowRepository.save(AssessmentWorkflow.builder()
                .id("pci").name("PCI").defaultWorkflow(false).archived(true)
                .statuses(new ArrayList<>(List.of("Draft", "Fieldwork", "Signed Off")))
                .newAssessmentStatus("Draft").inProgressStatus("Fieldwork").completedStatus("Signed Off")
                .statusColors(Map.of("Draft", "#111111"))
                .vulnerabilitySlas(new ArrayList<>(List.of(new VulnerabilitySla("CRITICAL", 7, 2))))
                .vulnerabilityStatuses(new ArrayList<>(List.of("Risk Accepted")))
                .remediationStages(new ArrayList<>(List.of(new RemediationStage("pci-live", "Live"))))
                .allowSelfPeerReview(true)
                .createdAt(created).updatedAt(created)
                .build());

        AssessmentWorkflow stored = workflowRepository.findById("pci").orElseThrow();
        assertThat(stored.getName()).isEqualTo("PCI");
        assertThat(stored.isDefaultWorkflow()).isFalse();
        assertThat(stored.isArchived()).isTrue();
        assertThat(stored.getStatuses()).containsExactly("Draft", "Fieldwork", "Signed Off");
        assertThat(stored.getNewAssessmentStatus()).isEqualTo("Draft");
        assertThat(stored.getInProgressStatus()).isEqualTo("Fieldwork");
        assertThat(stored.getCompletedStatus()).isEqualTo("Signed Off");
        assertThat(stored.getStatusColors()).containsEntry("Draft", "#111111");
        assertThat(stored.getVulnerabilitySlas()).containsExactly(new VulnerabilitySla("CRITICAL", 7, 2));
        assertThat(stored.getVulnerabilityStatuses()).containsExactly("Risk Accepted");
        assertThat(stored.getRemediationStages()).containsExactly(new RemediationStage("pci-live", "Live"));
        assertThat(stored.isAllowSelfPeerReview()).isTrue();
        assertThat(stored.getCreatedAt()).isEqualTo(created);
    }

    @Test
    void workflowNamesAreUnique() {
        workflowRepository.saveAndFlush(AssessmentWorkflow.builder().id("one").name("Same").build());

        assertThatThrownBy(() -> workflowRepository.saveAndFlush(
                AssessmentWorkflow.builder().id("two").name("Same").build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void assessmentTypesAndAssessmentsBelongToDefaultWorkflowUnlessToldOtherwise() {
        AssessmentType type = assessmentTypeRepository.save(AssessmentType.builder()
                .name("Type " + UUID.randomUUID()).build());
        typeIds.add(type.getId());
        Assessment assessment = assessmentRepository.save(Assessment.builder()
                .name("Assessment " + UUID.randomUUID()).assessmentTypeId(type.getId()).build());
        assessmentIds.add(assessment.getId());

        assertThat(type.getWorkflowId()).isEqualTo("default");
        assertThat(assessment.getWorkflowId()).isEqualTo("default");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT workflow_id FROM assessment_types WHERE id = ?", String.class, type.getId()))
                .isEqualTo("default");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT workflow_id FROM assessments WHERE id = ?", String.class, assessment.getId()))
                .isEqualTo("default");
    }

    @Test
    void isDefaultColumnIsNotNull() {
        Map<String, Object> column = jdbcTemplate.queryForMap(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = 'assessment_workflows' "
                        + "AND column_name = 'is_default'");
        assertThat(column.get("is_nullable")).isEqualTo("NO");
    }

    @Test
    void theWorkflowIdColumnsAreNotNullWithADefaultInTheDatabase() {
        for (String table : List.of("assessment_types", "assessments")) {
            Map<String, Object> column = jdbcTemplate.queryForMap(
                    "SELECT is_nullable, column_default FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = 'workflow_id'",
                    table);
            assertThat(column.get("is_nullable")).as(table).isEqualTo("NO");
            assertThat((String) column.get("column_default")).as(table).startsWith("'default'");
        }
    }
}
