package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.Retest;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.RetestRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.repository.VulnerabilityStageCompletionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retest evidence migration backfills {@code retest_report_generated_at} on upgrade, so every
 * assessment with past PASSED/FAILED retests doesn't show up as "ready for a retest report" the
 * moment the feature ships. Existing work counts as already reported through its latest completed
 * retest; only retests completed after the upgrade prompt a report.
 *
 * <p>The test profile's create-drop schema already has the column, so this runs the migration's SQL
 * directly against seeded rows whose stamp is null — the state an upgraded database is in right
 * after the ALTER.
 */
@SpringBootTest
@ActiveProfiles("test")
class RetestReportBackfillMigrationTest extends TestContainersConfig {

    private static final String MIGRATION = "db/migration/V20260926071149__retest_evidence_and_reports.sql";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private RetestRepository retestRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private VulnerabilityStageCompletionRepository stageCompletionRepository;

    @BeforeEach
    void setUp() {
        retestRepository.deleteAll();
        stageCompletionRepository.deleteAll();
        vulnerabilityRepository.deleteAll();
        assessmentRepository.deleteAll();
    }

    @Test
    void stampsEachAssessmentWithItsLatestCompletedRetestAndIsIdempotent() throws IOException {
        String retested = assessment(null);
        retest(retested, "PASSED", LocalDateTime.of(2026, 3, 1, 9, 0), null);
        retest(retested, "FAILED", LocalDateTime.of(2026, 4, 1, 9, 0), null);
        // Neither of these counts: a cancelled retest isn't a result, a deleted one is gone.
        retest(retested, "CANCELLED", LocalDateTime.of(2026, 5, 1, 9, 0), null);
        retest(retested, "PASSED", LocalDateTime.of(2026, 6, 1, 9, 0), LocalDateTime.of(2026, 6, 2, 9, 0));

        String onlyScheduled = assessment(null);
        retest(onlyScheduled, "SCHEDULED", null, null);

        LocalDateTime alreadyStamped = LocalDateTime.of(2026, 1, 15, 12, 0);
        String reported = assessment(alreadyStamped);
        retest(reported, "PASSED", LocalDateTime.of(2026, 2, 1, 9, 0), null);

        String noRetests = assessment(null);

        for (int run = 1; run <= 2; run++) {
            runMigration();

            assertThat(stamp(retested)).isEqualTo(LocalDateTime.of(2026, 4, 1, 9, 0));
            assertThat(stamp(onlyScheduled)).isNull();
            assertThat(stamp(reported)).isEqualTo(alreadyStamped);
            assertThat(stamp(noRetests)).isNull();
        }
    }

    private void runMigration() throws IOException {
        jdbcTemplate.execute(new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8));
    }

    private String assessment(LocalDateTime retestReportGeneratedAt) {
        return assessmentRepository.save(Assessment.builder()
                .name("A " + System.nanoTime()).assessmentTypeId("t").status("Completed")
                .retestReportGeneratedAt(retestReportGeneratedAt)
                .createdAt(LocalDateTime.now()).build()).getId();
    }

    private void retest(String assessmentId, String status, LocalDateTime closedDate, LocalDateTime deletedAt) {
        retestRepository.save(Retest.builder()
                .vulnerabilityId("v-" + System.nanoTime()).assessmentId(assessmentId).status(status)
                .closedDate(closedDate).deletedAt(deletedAt)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
    }

    private LocalDateTime stamp(String assessmentId) {
        return assessmentRepository.findById(assessmentId).orElseThrow().getRetestReportGeneratedAt();
    }
}
