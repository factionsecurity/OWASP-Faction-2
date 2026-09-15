package com.faction.clientportal.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The workflow indexes are built concurrently at startup, rebuilt when an interrupted build left one
 * invalid, and building them twice changes nothing. A build that fails is logged, never thrown.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkflowIndexInitializerTest extends TestContainersConfig {

    private static final String ASSESSMENTS = "idx_assessments_workflow_status";
    private static final String FINDINGS = "idx_vulnerabilities_assessment_status";
    private static final String STAGES = "idx_vulnerability_stage_completions_stage";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private WorkflowIndexInitializer initializer;
    @Autowired private ConcurrentIndexBuilder indexBuilder;

    @BeforeEach
    void dropIndexes() {
        jdbcTemplate.execute("DROP INDEX IF EXISTS " + ASSESSMENTS);
        jdbcTemplate.execute("DROP INDEX IF EXISTS " + FINDINGS);
        jdbcTemplate.execute("DROP INDEX IF EXISTS " + STAGES);
    }

    @Test
    void buildsTheThreeWorkflowIndexesOnApplicationReady() {
        initializer.onApplicationReady();

        assertIndex(ASSESSMENTS, "assessments", "(workflow_id, status)");
        assertIndex(FINDINGS, "vulnerabilities", "(assessment_id, status)");
        assertIndex(STAGES, "vulnerability_stage_completions", "(stage_id)");
    }

    @Test
    void disabledByConfigurationBuildsNothing() {
        new WorkflowIndexInitializer(indexBuilder, false).onApplicationReady();

        assertThat(exists(ASSESSMENTS)).isFalse();
        assertThat(exists(FINDINGS)).isFalse();
        assertThat(exists(STAGES)).isFalse();
    }

    @Test
    void runningTwiceKeepsTheSameIndexes() {
        initializer.ensureIndexes();
        long assessments = oid(ASSESSMENTS);
        long findings = oid(FINDINGS);
        long stages = oid(STAGES);

        initializer.ensureIndexes();

        assertThat(oid(ASSESSMENTS)).isEqualTo(assessments);
        assertThat(oid(FINDINGS)).isEqualTo(findings);
        assertThat(oid(STAGES)).isEqualTo(stages);
    }

    @Test
    void rebuildsAnIndexLeftInvalidByAnInterruptedBuild() {
        initializer.ensureIndexes();
        long before = oid(FINDINGS);
        // What a CREATE INDEX CONCURRENTLY killed part-way leaves behind. The container user is a superuser.
        jdbcTemplate.update("UPDATE pg_index SET indisvalid = false "
                + "WHERE indexrelid = (SELECT oid FROM pg_class WHERE relname = ?)", FINDINGS);

        initializer.ensureIndexes();

        assertThat(valid(FINDINGS)).isTrue();
        assertThat(oid(FINDINGS)).isNotEqualTo(before);
    }

    @Test
    void aBuildThatFailsIsLoggedNotThrown() {
        assertThatCode(() -> indexBuilder.ensure("idx_workflow_test_broken", "ON no_such_table (no_column)"))
                .doesNotThrowAnyException();
        assertThat(exists("idx_workflow_test_broken")).isFalse();
    }

    private void assertIndex(String name, String table, String columns) {
        String def = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = ? AND indexname = ?",
                String.class, table, name);
        assertThat(def).contains(columns).doesNotContain("WHERE");
        assertThat(valid(name)).isTrue();
    }

    private boolean exists(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_class WHERE relname = ?", Integer.class, name) > 0;
    }

    private boolean valid(String name) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT i.indisvalid FROM pg_class c JOIN pg_index i ON i.indexrelid = c.oid WHERE c.relname = ?",
                Boolean.class, name));
    }

    private long oid(String name) {
        return jdbcTemplate.queryForObject("SELECT oid FROM pg_class WHERE relname = ?", Long.class, name);
    }
}
