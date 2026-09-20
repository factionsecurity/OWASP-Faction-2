package com.faction.clientportal.repository;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Assessment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The nav badge's grouped counts split each status by workflow, so the service can judge "completed"
 * per workflow, and by assessment type, so the sidebar can badge each type's entry. Every scope
 * variant returns rows of {@code [workflowId, status, assessmentTypeId, count]} and skips deleted
 * assessments.
 *
 * <p>All six variants are asserted because they are not one query: the assigned tier is native SQL
 * while the rest are JPQL, so a change to the projection can reach five of them and miss the sixth.
 */
@SpringBootTest
@ActiveProfiles("test")
class AssessmentStatusCountsTest extends TestContainersConfig {

    private static final List<String> EXPECTED =
            List.of("default|Completed|type-1|1", "second-workflow|Completed|type-1|1");

    @Autowired private AssessmentRepository assessmentRepository;

    @BeforeEach
    void seed() {
        assessmentRepository.deleteAll();
        save("default", "user-1", List.of(), null);
        save("second-workflow", null, List.of("user-1"), null);
        save("second-workflow", "user-1", List.of("user-1"), LocalDateTime.now()); // deleted: never counted
    }

    @Test
    void all() {
        assertThat(flatten(assessmentRepository.countByWorkflowAndStatusGroupedAll()))
                .containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    @Test
    void membership() {
        assertThat(flatten(assessmentRepository.countByWorkflowAndStatusGroupedMembership(
                List.of("org-1"), List.of("app-elsewhere")))).containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    @Test
    void orgs() {
        assertThat(flatten(assessmentRepository.countByWorkflowAndStatusGroupedOrgs(List.of("org-1"))))
                .containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    @Test
    void owned() {
        assertThat(flatten(assessmentRepository.countByWorkflowAndStatusGroupedOwned(List.of("app-1"))))
                .containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    @Test
    void team() {
        assertThat(flatten(assessmentRepository.countByWorkflowAndStatusGroupedTeam(List.of("team-1"))))
                .containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    @Test
    void assigned() {
        assertThat(flatten(assessmentRepository.countByWorkflowAndStatusGroupedAssigned("user-1")))
                .containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    private void save(String workflowId, String assessorId, List<String> assessorIds, LocalDateTime deletedAt) {
        assessmentRepository.save(Assessment.builder()
                .name("Counts " + System.nanoTime())
                .organizationId("org-1")
                .applicationId("app-1")
                .teamId("team-1")
                .assessmentTypeId("type-1")
                .workflowId(workflowId)
                .status("Completed")
                .assessorId(assessorId)
                .assessorIds(new java.util.ArrayList<>(assessorIds))
                .deletedAt(deletedAt)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private static List<String> flatten(List<Object[]> rows) {
        return rows.stream()
                .map(r -> r[0] + "|" + r[1] + "|" + r[2] + "|" + ((Number) r[3]).longValue())
                .toList();
    }
}
