package com.faction.clientportal.perf;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.config.VulnerabilitySlaIndexInitializer;
import com.faction.clientportal.config.WorkflowIndexInitializer;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentSearchCriteria;
import com.faction.clientportal.repository.AssessmentWorkflowRepository;
import com.faction.clientportal.repository.RemediationQueueCriteria;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.repository.VulnerabilitySearchCriteria;
import com.faction.clientportal.scheduled.VulnerabilityPastDueJob;
import com.faction.clientportal.service.SlaRecalculationService;
import com.faction.clientportal.service.WorkflowCatalog;
import com.faction.clientportal.service.WorkflowCatalogService;
import com.faction.clientportal.testsupport.TestWorkflows;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance acceptance for assessment workflows (spec §5). Gated by
 * {@code @EnabledIfSystemProperty(named = "perf.check", matches = "true")}, which only
 * {@code mise run perf-check} sets — the class-name pattern alone ({@code *PerfCheck} vs. surefire's
 * default {@code *Test}/{@code *Tests}/{@code *TestCase} includes) is not sufficient, because a
 * negation-only {@code -Dtest} (as {@code mise run backend-test-quick}'s {@code -Dtest='!@IntegrationTest'}
 * does) drops surefire 3.5.x's default includes entirely and would otherwise select this class too. The
 * property gate skips it regardless of how {@code -Dtest} is spelled.
 *
 * <p>Seeds this run's throwaway Testcontainers database with {@code generate_series}, times the concurrent
 * index builds, captures the SQL each hot path really sends, replays every statement with its bound values
 * under {@code EXPLAIN (ANALYZE, BUFFERS)}, times SLA recalculations, and writes
 * {@code target/perf/assessment-workflows-perf.md}. {@code -Dperf.scale=0.01} is a quick smoke run of the
 * harness; the thresholds are enforced only at scale 1.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "perf.check", matches = "true")
@Import(AssessmentWorkflowsPerfCheck.CaptureConfig.class)
class AssessmentWorkflowsPerfCheck extends TestContainersConfig {

    static final StatementCapture CAPTURE = new StatementCapture();

    private static final double SCALE = Double.parseDouble(System.getProperty("perf.scale", "1"));
    private static final int ASSESSMENTS = scaled(50_000);
    private static final int FINDINGS = scaled(1_000_000);
    private static final int RETESTS = scaled(20_000);
    private static final int STAGE_COMPLETIONS = scaled(100_000);
    private static final int WORKFLOWS = 20;
    private static final double THRESHOLD_MS = 300;
    private static final Path REPORT = Path.of("target", "perf", "assessment-workflows-perf.md");
    private static final String ONE_WORKFLOW = "perf-wf-01";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> STARTUP_INDEXES = List.of(
            "idx_assessments_workflow_status", "idx_vulnerabilities_assessment_status",
            "idx_vulnerability_stage_completions_stage",
            "idx_vulnerabilities_open_due_at", "idx_vulnerabilities_open_warning_at",
            "idx_vulnerabilities_alltime_summary");
    /** Mirrors VulnerabilityPastDueJob.SKIPPED_STATUSES, which is package-private. */
    private static final Set<String> PAST_DUE_SKIPPED =
            Set.of("Past Due", "Closed", "In Retest", "Passed Retest", "Failed Retest");

    @TestConfiguration
    static class CaptureConfig {
        @Bean
        static BeanPostProcessor capturingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource dataSource ? CAPTURE.wrap(dataSource) : bean;
                }
            };
        }
    }

    /** Runs every minute; it would rewrite the seeded findings while they are measured. */
    @MockBean private VulnerabilityPastDueJob pastDueJob;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private AssessmentWorkflowRepository workflowRepository;
    @Autowired private WorkflowCatalogService workflowCatalogService;
    @Autowired private SlaRecalculationService slaRecalculationService;
    @Autowired private WorkflowIndexInitializer workflowIndexInitializer;
    @Autowired private VulnerabilitySlaIndexInitializer slaIndexInitializer;

    private record Row(String check, int index, String sql, double planningMs, double executionMs,
                       List<String> seqScans, String plan) {
        boolean passed() {
            return executionMs < THRESHOLD_MS && !seqScans.contains("vulnerabilities");
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final Map<String, Double> indexSeconds = new LinkedHashMap<>();
    private final List<String> recalculations = new ArrayList<>();

    @Test
    void measure() throws Exception {
        // Dropped before seeding (faster inserts), then rebuilt and timed at full size.
        STARTUP_INDEXES.forEach(index -> jdbcTemplate.execute("DROP INDEX IF EXISTS " + index));
        seed();
        jdbcTemplate.execute("VACUUM ANALYZE");
        indexSeconds.put("Workflow indexes (3)", seconds(workflowIndexInitializer::ensureIndexes));
        indexSeconds.put("SLA due-date + all-time summary indexes (3)", seconds(slaIndexInitializer::ensureIndexes));
        for (String index : STARTUP_INDEXES) {
            assertThat(valid(index)).as(index + " built and valid").isTrue();
        }
        jdbcTemplate.execute("ANALYZE");

        WorkflowCatalog catalog = workflowCatalogService.load();
        List<String> otherThanDefault = catalog.workflows(true).stream()
                .map(AssessmentWorkflow::getId)
                .filter(id -> !id.equals(catalog.defaultWorkflow().getId()))
                .toList();
        LocalDateTime now = LocalDateTime.now();

        // Sanity check that the seed's per-workflow attributes are not all riding on the same modulus
        // (see the comment above the findings INSERT): both ONE_WORKFLOW and Default Workflow should
        // show several distinct assessment statuses and several distinct finding severities, not one.
        logSeedDiversity(ONE_WORKFLOW);
        logSeedDiversity(catalog.defaultWorkflow().getId());

        check("Remediation queue, first page", true, () -> vulnerabilityRepository.listRemediationDue(
                RemediationQueueCriteria.builder().build(), PageRequest.of(0, 50)));
        check("Remediation queue, page 200", true, () -> vulnerabilityRepository.listRemediationDue(
                RemediationQueueCriteria.builder().build(), PageRequest.of(200, 50)));
        check("Remediation queue badge counts", true, () -> vulnerabilityRepository.countRemediationBuckets(
                RemediationQueueCriteria.builder().build()));
        // Split in phase 5c (task 4): the open-only half rides the SLA partial indexes, the
        // all-time half needs its own covering index (task 5) — measured separately so the report
        // shows which half is still failing the no-seq-scan rule.
        check("Severity summary tiles, open-only", true, () -> vulnerabilityRepository.summarizeOpenBySeverity(
                VulnerabilitySearchCriteria.builder().includeClosed(true).build()));
        check("Severity summary tiles, all-time", true, () -> vulnerabilityRepository.summarizeAllTimeBySeverity(
                List.of(0, 1, 2, 3), VulnerabilitySearchCriteria.builder().includeClosed(true).build()));
        check("Assessment search, exclude completed", true, () -> assessmentRepository.searchAdvanced(
                AssessmentSearchCriteria.builder().excludeCompleted(true)
                        .completed(catalog.completedStatusFilter()).now(now).build(),
                PageRequest.of(0, 50)));
        check("Assessment search, past due", true, () -> assessmentRepository.searchAdvanced(
                AssessmentSearchCriteria.builder().pastDue(true)
                        .completed(catalog.completedStatusFilter()).now(now).build(),
                PageRequest.of(0, 50)));
        check("Nav badge counts", true, () -> assessmentRepository.countByWorkflowAndStatusGroupedAll());
        check("Past-due job selection", true, () -> vulnerabilityRepository.findPastDueCandidatesAfterId(
                "", now, PAST_DUE_SKIPPED, PageRequest.of(0, 500)));
        // Record-only: running it would load every digest candidate as an entity.
        check("Digest selection", false, () -> vulnerabilityRepository.findDigestCandidates(now));
        check("Recalculation batch, one workflow", true, () -> vulnerabilityRepository.findOpenInWorkflowAfterId(
                "", ONE_WORKFLOW, PageRequest.of(0, 500)));
        check("Recalculation batch, Default Workflow", true,
                () -> vulnerabilityRepository.findOpenOutsideWorkflowsAfterId("", otherThanDefault, PageRequest.of(0, 500)));
        checkLiteral("Guard (phase 4): assessments in a status",
                "SELECT count(*) FROM assessments WHERE workflow_id = ? AND status = ? AND deleted_at IS NULL",
                ONE_WORKFLOW, "Fieldwork");
        checkLiteral("Guard (phase 4): findings in a vulnerability status",
                "SELECT count(*) FROM vulnerabilities v JOIN assessments a ON a.id = v.assessment_id"
                        + " WHERE a.workflow_id = ? AND v.status = ? AND v.deleted_at IS NULL",
                ONE_WORKFLOW, "Risk Accepted");
        checkLiteral("Guard (phase 4): remediation stage in use",
                "SELECT count(*) FROM vulnerability_stage_completions WHERE stage_id = ?", "second-qa");

        recalculate(ONE_WORKFLOW);
        recalculate(catalog.defaultWorkflow().getId());

        writeReport();
        System.out.println("Performance report: " + REPORT.toAbsolutePath());
        if (SCALE == 1.0) {
            assertThat(rows.stream().filter(r -> !r.passed()).map(r -> r.check() + " #" + r.index()).toList())
                    .as("statements at or over %s ms, or seq-scanning vulnerabilities; see %s",
                            THRESHOLD_MS, REPORT.toAbsolutePath())
                    .isEmpty();
        }
    }

    // ── Seeding ────────────────────────────────────────────────────────────────

    private void seed() {
        workflowCatalogService.load(); // creates Default Workflow if bootstrap has not
        for (int n = 1; n < WORKFLOWS; n++) {
            AssessmentWorkflow workflow = TestWorkflows.secondWorkflow();
            workflow.setId(String.format(Locale.ROOT, "perf-wf-%02d", n));
            workflow.setName(String.format(Locale.ROOT, "Perf Workflow %02d", n));
            workflowRepository.save(workflow);
        }
        execute("""
                INSERT INTO organizations (id, name, remediation_owner_ids)
                SELECT 'perf-org-' || g, 'Perf Org ' || g, '[]'::jsonb FROM generate_series(1, 200) g""");
        execute("""
                INSERT INTO applications (id, name, organization_id, created_at)
                SELECT 'perf-app-' || g, 'Perf App ' || g, 'perf-org-' || (g % 200 + 1), now()
                FROM generate_series(1, 2000) g""");
        // One workflow in 20 is Default Workflow; 60% of assessments completed, 2% deleted. The status
        // bucket and the deleted flag use divisions by 7 and 11 — coprime to the workflow's own modulus
        // (20) — so every workflow ends up with a spread of statuses instead of exactly one: with g % 10
        // (10 | 20) or g % 50 (a multiple of 20), the bucket a workflow lands in is fully determined by
        // its own g % 20, so e.g. perf-wf-01's assessments were all "Fieldwork" and the phase-4 guard
        // for perf-wf-01 + Fieldwork matched zero rows.
        execute("""
                INSERT INTO assessments (id, name, application_id, organization_id, assessment_type_id, team_id,
                    workflow_id, status, assessor_id, assessor_ids, planned_end_date, completed_date, deleted_at,
                    peer_review_status, created_at, updated_at)
                SELECT md5('a' || g)::uuid::text, 'Perf Assessment ' || g,
                       'perf-app-' || (g % 2000 + 1), 'perf-org-' || ((g % 2000 + 1) % 200 + 1),
                       'perf-type-' || (g % 10 + 1), 'perf-team-' || (g % 50 + 1),
                       wf.id,
                       CASE WHEN (g / 7) % 10 < 6 THEN wf.done WHEN (g / 7) % 10 < 8 THEN wf.working ELSE wf.fresh END,
                       'perf-user-' || (g % 100 + 1), jsonb_build_array('perf-user-' || (g % 100 + 1)),
                       now() - make_interval(days => g % 1095) + interval '30 days',
                       CASE WHEN (g / 7) % 10 < 6 THEN now() - make_interval(days => g % 1095) END,
                       CASE WHEN (g / 11) % 50 = 0 THEN now() END,
                       0, now() - make_interval(days => g % 1095), now()
                FROM generate_series(1, {assessments}) g
                CROSS JOIN LATERAL (
                    SELECT CASE WHEN g % 20 = 0 THEN 'default' ELSE 'perf-wf-' || lpad((g % 20)::text, 2, '0') END AS id,
                           CASE WHEN g % 20 = 0 THEN 'Completed' ELSE 'Signed Off' END AS done,
                           CASE WHEN g % 20 = 0 THEN 'Testing' ELSE 'Fieldwork' END AS working,
                           CASE WHEN g % 20 = 0 THEN 'New' ELSE 'Draft' END AS fresh) wf""");
        // Opened over three years. Findings older than 120 days are 90% closed; newer ones are all open.
        // Severity 4 (informational) has no SLA. 1% deleted, 0.5% Risk Accepted, 2% Exception, and six in
        // seven overdue open findings already marked Past Due.
        //
        // A finding's assessment is `g % {assessments} + 1`, and {assessments} is a multiple of 20 (the
        // workflow count), so a finding's workflow is fixed by `g % 20`. Every attribute below divides
        // its own generator by a value coprime to 20 (3, 7, 11, 13, 17, 19) before taking the modulus, so
        // none of them is a function of `g % 20` — unlike the original `g % 5` severity, `g % 10` closed,
        // `g % 100` deleted, `g % 200` Risk Accepted, `g % 50` Exception, and `g % 7` past-due, which were
        // (5, 10, 100, 200 and 50 all being multiples or divisors of 20) — and every Default Workflow
        // finding (`g % 20 == 19`) landed on severity 4 (informational, no SLA), so its recalculation
        // never had anything to change.
        execute("""
                INSERT INTO vulnerabilities (id, assessment_id, name, severity, status, opened_at, closed_at,
                    due_at, warning_at, deleted_at, exception_expiry_date, display_order, comments, subscribers,
                    field_definitions, field_values, exception_files, past_due_reminder_count, created_at, updated_at)
                SELECT md5('v' || g)::uuid::text, md5('a' || (g % {assessments} + 1))::uuid::text,
                       'Perf Finding ' || g, (g / 3) % 5, s.status, b.opened_at,
                       CASE WHEN b.closed THEN b.opened_at + interval '30 days' END,
                       d.due_at, d.due_at - interval '20 days',
                       CASE WHEN b.deleted THEN now() END,
                       CASE WHEN s.status = 'Exception' THEN now() + interval '90 days' END,
                       g % 40, '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, '[]'::jsonb, 0, b.opened_at, now()
                FROM generate_series(1, {findings}) g
                CROSS JOIN LATERAL (
                    SELECT now() - make_interval(days => g % 1095) AS opened_at,
                           (g % 1095) > 120 AND (g / 7) % 10 <> 0 AS closed,
                           (g / 11) % 100 = 50 AS deleted) b
                CROSS JOIN LATERAL (
                    SELECT CASE WHEN (g / 3) % 5 = 4 OR b.closed OR b.deleted THEN NULL
                                ELSE b.opened_at + make_interval(days => (ARRAY[30, 60, 90, 180])[(g / 3) % 5 + 1]) END AS due_at) d
                CROSS JOIN LATERAL (
                    SELECT CASE WHEN b.closed THEN 'Closed'
                                WHEN (g / 13) % 200 = 10 THEN 'Risk Accepted'
                                WHEN (g / 17) % 50 = 20 THEN 'Exception'
                                WHEN d.due_at < now() AND (g / 19) % 7 <> 0 THEN 'Past Due'
                                ELSE 'Open' END AS status) s""");
        execute("""
                INSERT INTO retests (id, vulnerability_id, assessment_id, status, scheduled_start_date,
                    scheduled_end_date, closed_date, assigned_assessor_ids, created_at, updated_at)
                SELECT md5('r' || g)::uuid::text,
                       md5('v' || ((g * 37) % {findings} + 1))::uuid::text,
                       md5('a' || (((g * 37) % {findings} + 1) % {assessments} + 1))::uuid::text,
                       (ARRAY['REQUESTED', 'SCHEDULED', 'IN_PROGRESS', 'PASSED', 'FAILED', 'PASSED', 'FAILED', 'PASSED'])[g % 8 + 1],
                       now() - make_interval(days => g % 60),
                       now() - make_interval(days => g % 60) + interval '5 days',
                       CASE WHEN g % 8 >= 3 THEN now() - make_interval(days => g % 60) + interval '5 days' END,
                       '[]'::jsonb, now() - make_interval(days => g % 60), now()
                FROM generate_series(1, {retests}) g""");
        execute("""
                INSERT INTO vulnerability_stage_completions (id, vulnerability_id, stage_id, completed_at, completed_by)
                SELECT md5('s' || g)::uuid::text, md5('v' || g)::uuid::text,
                       CASE WHEN g % 2 = 0 THEN 'second-qa' ELSE 'second-live' END, now(), 'perf-user-1'
                FROM generate_series(1, {stages}) g""");
    }

    private void execute(String template) {
        jdbcTemplate.execute(template
                .replace("{assessments}", String.valueOf(ASSESSMENTS))
                .replace("{findings}", String.valueOf(FINDINGS))
                .replace("{retests}", String.valueOf(RETESTS))
                .replace("{stages}", String.valueOf(STAGE_COMPLETIONS)));
    }

    // ── Measuring ──────────────────────────────────────────────────────────────

    /** Captures the statements {@code call} sends (inside one transaction) and explains each one. */
    private void check(String name, boolean execute, Runnable call) {
        List<StatementCapture.CapturedStatement> statements =
                CAPTURE.capture(execute, () -> transactionTemplate.executeWithoutResult(status -> call.run()));
        assertThat(statements).as(name + " sent no SQL").isNotEmpty();
        for (int i = 0; i < statements.size(); i++) {
            rows.add(explain(name, i + 1, statements.get(i)));
        }
    }

    /**
     * Runs {@code sql} for real first, so the report can show how many rows the guard actually matched
     * (a guard that always matches zero rows — e.g. a status paired with a workflow that never reaches it
     * — would pass the threshold vacuously and prove nothing), then captures the same statement for its
     * EXPLAIN plan.
     */
    private void checkLiteral(String name, String sql, String... values) throws NoSuchMethodException {
        long matched = jdbcTemplate.queryForObject(sql, Long.class, (Object[]) values);
        Method setString = PreparedStatement.class.getMethod("setString", int.class, String.class);
        List<StatementCapture.BindCall> binds = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            binds.add(new StatementCapture.BindCall(setString, new Object[]{i + 1, values[i]}));
        }
        rows.add(explain(String.format(Locale.ROOT, "%s (matched %,d row%s)", name, matched, matched == 1 ? "" : "s"),
                1, new StatementCapture.CapturedStatement(sql, binds)));
    }

    private Row explain(String name, int index, StatementCapture.CapturedStatement statement) {
        String json = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> StatementCapture.explain(connection, statement));
        try {
            JsonNode root = MAPPER.readTree(json).get(0);
            List<String> seqScans = new ArrayList<>();
            StringBuilder plan = new StringBuilder();
            render(root.get("Plan"), 0, plan, seqScans);
            return new Row(name, index, statement.sql(), root.path("Planning Time").asDouble(),
                    root.path("Execution Time").asDouble(), seqScans, plan.toString());
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable plan for " + name, e);
        }
    }

    private static void render(JsonNode node, int depth, StringBuilder out, List<String> seqScans) {
        String type = node.path("Node Type").asText();
        String relation = node.path("Relation Name").asText("");
        if ("Seq Scan".equals(type)) {
            seqScans.add(relation);
        }
        out.append("  ".repeat(depth)).append(type);
        if (!relation.isEmpty()) {
            out.append(" on ").append(relation);
        }
        if (node.has("Index Name")) {
            out.append(" using ").append(node.get("Index Name").asText());
        }
        out.append(String.format(Locale.ROOT, "  (%.1f ms, rows %d, loops %d, buffers hit %d read %d)%n",
                node.path("Actual Total Time").asDouble(), node.path("Actual Rows").asLong(),
                node.path("Actual Loops").asLong(), node.path("Shared Hit Blocks").asLong(),
                node.path("Shared Read Blocks").asLong()));
        for (JsonNode child : node.path("Plans")) {
            render(child, depth + 1, out, seqScans);
        }
    }

    private void recalculate(String workflowId) {
        long start = System.nanoTime();
        SlaRecalculationService.RecalculationResult result = slaRecalculationService.recalculateOpenFindings(workflowId);
        recalculations.add(String.format(Locale.ROOT, "| %s | %,d | %,d | %.1f |%n", workflowId,
                result.recalculated(), result.clearedPastDue(), (System.nanoTime() - start) / 1e9));
    }

    private static double seconds(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return (System.nanoTime() - start) / 1e9;
    }

    /** Prints how many distinct assessment statuses and finding severities a workflow's seed data spans. */
    private void logSeedDiversity(String workflowId) {
        Integer statuses = jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT status) FROM assessments WHERE workflow_id = ?", Integer.class, workflowId);
        Integer severities = jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT v.severity) FROM vulnerabilities v"
                        + " JOIN assessments a ON a.id = v.assessment_id WHERE a.workflow_id = ?",
                Integer.class, workflowId);
        System.out.println(String.format(Locale.ROOT,
                "Seed diversity: workflow %s has %d distinct assessment status(es), %d distinct finding severit(y/ies)",
                workflowId, statuses, severities));
    }

    private boolean valid(String index) {
        return Boolean.TRUE.equals(jdbcTemplate.query(
                "SELECT i.indisvalid FROM pg_class c JOIN pg_index i ON i.indexrelid = c.oid WHERE c.relname = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null, index));
    }

    private static int scaled(int count) {
        return Math.max(1, (int) Math.round(count * SCALE));
    }

    // ── Report ─────────────────────────────────────────────────────────────────

    private void writeReport() throws IOException {
        long open = jdbcTemplate.queryForObject("SELECT count(*) FROM vulnerabilities"
                + " WHERE opened_at IS NOT NULL AND closed_at IS NULL AND deleted_at IS NULL", Long.class);
        long inWarningWindow = jdbcTemplate.queryForObject("SELECT count(*) FROM vulnerabilities"
                + " WHERE opened_at IS NOT NULL AND closed_at IS NULL AND deleted_at IS NULL"
                + " AND warning_at < CAST(current_date + 1 AS timestamp)", Long.class);

        StringBuilder md = new StringBuilder("# Assessment workflows performance check\n\n");
        md.append("- Run: ").append(LocalDateTime.now().withNano(0)).append('\n');
        md.append("- Database: ").append(jdbcTemplate.queryForObject("SELECT version()", String.class)).append('\n');
        md.append(String.format(Locale.ROOT,
                "- Scale %.2f: %,d assessments, %,d findings, %d workflows, %,d retests, %,d stage completions%n",
                SCALE, ASSESSMENTS, FINDINGS, WORKFLOWS, RETESTS, STAGE_COMPLETIONS));
        md.append("- Shape: assessments 60% completed, 2% deleted, 1 in 20 on Default Workflow; findings opened over"
                + " three years, older than 120 days 90% closed, 1% deleted, 2% Exception, 0.5% Risk Accepted\n");
        md.append(String.format(Locale.ROOT, "- Open findings: %,d; in the remediation queue's warning window: %,d%n",
                open, inWarningWindow));
        md.append("- Thresholds: execution under 300 ms and no Seq Scan on vulnerabilities (enforced at scale 1)\n");
        md.append("- Caveat: each EXPLAIN replay runs immediately after the application's own execution of the same"
                + " statement (buffer cache warm), and plans against the literal bound values rather than a generic"
                + " plan the application may fall back to after repeated executions of the same statement shape —"
                + " both effects bias these numbers toward PASS relative to a cold, generically-planned production run.\n\n");

        md.append("## Index builds (CREATE INDEX CONCURRENTLY)\n\n| Indexes | Seconds |\n|---|---|\n");
        indexSeconds.forEach((indexes, secs) ->
                md.append(String.format(Locale.ROOT, "| %s | %.1f |%n", indexes, secs)));

        md.append("\n## SLA recalculation\n\n| Workflow | Recalculated | Returned to Open | Seconds |\n|---|---|---|---|\n");
        recalculations.forEach(md::append);

        md.append("\n## Statements\n\n| Check | Statement | Planning ms | Execution ms | Seq scans | Result |\n")
                .append("|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            md.append(String.format(Locale.ROOT, "| %s | %d | %.1f | %.1f | %s | %s |%n", r.check(), r.index(),
                    r.planningMs(), r.executionMs(), r.seqScans().isEmpty() ? "none" : String.join(", ", r.seqScans()),
                    r.passed() ? "PASS" : "FAIL"));
        }

        md.append("\n## Carried\n\nNone.\n\n## Plans\n");
        for (Row r : rows) {
            md.append("\n### ").append(r.check()).append(" #").append(r.index())
                    .append("\n\n```sql\n").append(r.sql()).append("\n```\n\n```text\n").append(r.plan()).append("```\n");
        }
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, md);
    }
}
