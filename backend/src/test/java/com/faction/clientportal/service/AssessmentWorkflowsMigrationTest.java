package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assessment_workflows migration turns an existing installation's single workflow configuration
 * into Default Workflow and puts every assessment type and assessment on it, changing no setting,
 * status or stage id. On a fresh database it does nothing: Hibernate creates the schema and
 * bootstrap seeds Default Workflow.
 *
 * <p>Runs in a throwaway schema holding today's tables, because the test profile's create-drop
 * schema already has everything the migration adds.
 */
@SpringBootTest
@ActiveProfiles("test")
class AssessmentWorkflowsMigrationTest extends TestContainersConfig {

    private static final String MIGRATION = "db/migration/V20260914202448__assessment_workflows.sql";

    /** Today's shape of the three tables the migration reads or alters (the columns it touches). */
    private static final String LEGACY_SCHEMA = """
            CREATE TABLE assessment_workflow_config (
                id VARCHAR(255) NOT NULL PRIMARY KEY,
                completed_status VARCHAR(255),
                in_progress_status VARCHAR(255),
                new_assessment_status VARCHAR(255),
                status_colors JSONB,
                statuses JSONB,
                vulnerability_slas JSONB,
                vulnerability_statuses JSONB,
                allow_self_peer_review BOOLEAN NOT NULL DEFAULT FALSE,
                remediation_stages JSONB);
            CREATE TABLE assessment_types (id VARCHAR(255) NOT NULL PRIMARY KEY, name VARCHAR(255) NOT NULL);
            CREATE TABLE assessments (id VARCHAR(255) NOT NULL PRIMARY KEY, status VARCHAR(255));
            """;

    private static final String CUSTOMISED_SINGLETON = """
            INSERT INTO assessment_workflow_config (id, completed_status, in_progress_status,
                new_assessment_status, status_colors, statuses, vulnerability_slas, vulnerability_statuses,
                allow_self_peer_review, remediation_stages)
            VALUES ('singleton', 'Done', 'Testing', 'Open', '{"Done":"#00ff00"}',
                '["Open","Testing","Done"]', '[{"severity":"HIGH","pastDueDays":14,"warningDays":4}]',
                '["Risk Accepted"]', TRUE, '[{"id":"a1b2","name":"QA"},{"id":"c3d4","name":"Live"}]');
            """;

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void onAFreshDatabaseItDoesNothing() {
        inScratchSchema(db -> {
            runMigration(db);

            assertThat(db.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema()",
                    Integer.class)).isZero();
        });
    }

    @Test
    void copiesTheConfigurationIntoDefaultWorkflowUnchanged() {
        inScratchSchema(db -> {
            db.execute(LEGACY_SCHEMA);
            db.execute(CUSTOMISED_SINGLETON);

            runMigration(db);

            Map<String, Object> row = db.queryForMap("SELECT * FROM assessment_workflows WHERE id = 'default'");
            assertThat(row.get("name")).isEqualTo("Default Workflow");
            assertThat(row.get("is_default")).isEqualTo(true);
            assertThat(row.get("archived")).isEqualTo(false);
            assertThat(row.get("new_assessment_status")).isEqualTo("Open");
            assertThat(row.get("in_progress_status")).isEqualTo("Testing");
            assertThat(row.get("completed_status")).isEqualTo("Done");
            assertThat(row.get("allow_self_peer_review")).isEqualTo(true);
            assertJson(row.get("statuses"), "[\"Open\",\"Testing\",\"Done\"]");
            assertJson(row.get("status_colors"), "{\"Done\":\"#00ff00\"}");
            assertJson(row.get("vulnerability_slas"), "[{\"severity\":\"HIGH\",\"pastDueDays\":14,\"warningDays\":4}]");
            assertJson(row.get("vulnerability_statuses"), "[\"Risk Accepted\"]");
            // Stage ids unchanged, so stage completions and VULNERABILITY_CLOSED:<stageId> settings stay valid.
            assertJson(row.get("remediation_stages"), "[{\"id\":\"a1b2\",\"name\":\"QA\"},{\"id\":\"c3d4\",\"name\":\"Live\"}]");
            assertThat(row.get("created_at")).isNotNull();
            assertThat(row.get("updated_at")).isNotNull();
            assertThat(db.queryForObject("SELECT count(*) FROM assessment_workflows", Integer.class)).isEqualTo(1);
        });
    }

    @Test
    void putsEveryExistingAssessmentTypeAndAssessmentOnDefaultWorkflow() {
        inScratchSchema(db -> {
            db.execute(LEGACY_SCHEMA);
            db.execute(CUSTOMISED_SINGLETON);
            db.execute("INSERT INTO assessment_types (id, name) VALUES ('t1', 'Web'), ('t2', 'PCI')");
            db.execute("INSERT INTO assessments (id, status) VALUES ('a1', 'Open'), ('a2', 'Done')");

            runMigration(db);

            assertThat(db.queryForList("SELECT DISTINCT workflow_id FROM assessment_types", String.class))
                    .containsExactly("default");
            assertThat(db.queryForList("SELECT DISTINCT workflow_id FROM assessments", String.class))
                    .containsExactly("default");
            // Rows inserted later without a workflow id land on Default Workflow too.
            db.execute("INSERT INTO assessments (id, status) VALUES ('a3', 'Open')");
            assertThat(db.queryForObject("SELECT workflow_id FROM assessments WHERE id = 'a3'", String.class))
                    .isEqualTo("default");
            // Statuses are not touched.
            assertThat(db.queryForList("SELECT status FROM assessments ORDER BY id", String.class))
                    .containsExactly("Open", "Done", "Open");
        });
    }

    @Test
    void withNoConfigurationRowItCreatesTheTableButNoWorkflow() {
        inScratchSchema(db -> {
            db.execute(LEGACY_SCHEMA);

            runMigration(db);

            // Bootstrap seeds Default Workflow with the defaults at startup, as getConfig() did before.
            assertThat(db.queryForObject("SELECT count(*) FROM assessment_workflows", Integer.class)).isZero();
        });
    }

    @Test
    void runningItAgainChangesNothing() {
        inScratchSchema(db -> {
            db.execute(LEGACY_SCHEMA);
            db.execute(CUSTOMISED_SINGLETON);
            runMigration(db);
            db.execute("UPDATE assessment_workflows SET completed_status = 'Signed Off' WHERE id = 'default'");

            runMigration(db);

            assertThat(db.queryForObject("SELECT count(*) FROM assessment_workflows", Integer.class)).isEqualTo(1);
            assertThat(db.queryForObject(
                    "SELECT completed_status FROM assessment_workflows WHERE id = 'default'", String.class))
                    .isEqualTo("Signed Off");
        });
    }

    @Test
    void theTableAndColumnsMatchWhatHibernateCreates() {
        Set<Map<String, Object>> hibernateTable = columns(jdbcTemplate, "assessment_workflows");
        Set<Map<String, Object>> hibernateTypeColumn = column(jdbcTemplate, "assessment_types", "workflow_id");
        Set<Map<String, Object>> hibernateAssessmentColumn = column(jdbcTemplate, "assessments", "workflow_id");
        assertThat(hibernateTable).as("Hibernate created assessment_workflows in the test schema").isNotEmpty();

        inScratchSchema(db -> {
            db.execute(LEGACY_SCHEMA);
            runMigration(db);

            assertThat(columns(db, "assessment_workflows")).isEqualTo(hibernateTable);
            assertThat(column(db, "assessment_types", "workflow_id")).isEqualTo(hibernateTypeColumn);
            assertThat(column(db, "assessments", "workflow_id")).isEqualTo(hibernateAssessmentColumn);
            assertThat(db.queryForObject(
                    "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() "
                            + "AND indexname = 'idx_assessment_workflows_name'", String.class))
                    .contains("UNIQUE").contains("(name)");
        });
    }

    private Set<Map<String, Object>> columns(JdbcTemplate db, String table) {
        return new HashSet<>(db.queryForList(
                "SELECT column_name, data_type, character_maximum_length, datetime_precision, is_nullable "
                        + "FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ?",
                table));
    }

    private Set<Map<String, Object>> column(JdbcTemplate db, String table, String column) {
        return new HashSet<>(db.queryForList(
                "SELECT column_name, data_type, character_maximum_length, is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                table, column));
    }

    private void assertJson(Object actual, String expected) {
        try {
            assertThat(objectMapper.readTree(actual.toString())).isEqualTo(objectMapper.readTree(expected));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void runMigration(JdbcTemplate db) {
        try {
            db.execute(new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    /** Runs the body on one connection whose search_path is a new empty schema, then drops it. */
    private void inScratchSchema(Consumer<JdbcTemplate> body) {
        String schema = "wf_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            try {
                body.accept(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
            } finally {
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        } catch (java.sql.SQLException e) {
            throw new AssertionError(e);
        }
    }
}
