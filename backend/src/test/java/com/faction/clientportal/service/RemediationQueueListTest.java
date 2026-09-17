package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.RemediationRowDto;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.VulnerabilitySla;
import com.faction.clientportal.model.AssignedUser;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Organization;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.Retest;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentWorkflowRepository;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.RemediationQueueCriteria;
import com.faction.clientportal.repository.RetestRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.repository.VulnerabilityRepositoryCustom.RemediationDueRow;
import com.faction.clientportal.security.RequiresPermissionAuthorizationManager;
import com.faction.clientportal.testsupport.TestWorkflows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The interleaved remediation queue ({@link RemediationQueueService#list}): open tracked
 * vulnerabilities at/past their SLA warning threshold UNIONed with open retests, ordered as one
 * sequence (urgent → warning → not-yet-due, then due date), scoped, searched, paginated, and enriched
 * with joined names and each vuln row's last retest result. Exercises the real UNION SQL.
 *
 * <p>SLA config: CRITICAL 7/3, HIGH 30/15, MEDIUM 90/45, LOW 180/90 — so warning thresholds
 * (pastDueDays − warningDays) are CRITICAL 4, HIGH 15, MEDIUM 45, LOW 90 and the due date (pastDueDays)
 * is CRITICAL 7, HIGH 30, MEDIUM 90, LOW 180.
 */
@SpringBootTest
@ActiveProfiles("test")
class RemediationQueueListTest extends TestContainersConfig {

    @Autowired private RemediationQueueService service;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private RetestRepository retestRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AssessmentWorkflowRepository workflowConfigRepository;
    @Autowired private SlaService slaService;

    private static final Pageable PAGE = PageRequest.of(0, 50);

    // A default org/app/assessment for tests that don't care about scope.
    private String orgId;
    private String appId;
    private String assessmentId;

    @BeforeEach
    void setUp() {
        vulnerabilityRepository.deleteAll();
        retestRepository.deleteAll();
        assessmentRepository.deleteAll();
        applicationRepository.deleteAll();
        organizationRepository.deleteAll();
        userRepository.deleteAll();
        workflowConfigRepository.deleteAll();

        configureSlas(
                new VulnerabilitySla("CRITICAL", 7, 3),
                new VulnerabilitySla("HIGH", 30, 15),
                new VulnerabilitySla("MEDIUM", 90, 45),
                new VulnerabilitySla("LOW", 180, 90));

        orgId = organization("Acme").getId();
        appId = application(orgId, "Payments API").getId();
        assessmentId = assessment(orgId, appId, "Q3 Pentest");
    }

    private List<RemediationRowDto> list() {
        return list(null, superAdmin());
    }

    private List<RemediationRowDto> list(String search, Authentication auth) {
        return service.list(search, null, null, null, null, null, null, null, false, PAGE, auth).getContent();
    }

    /** Filtered list as a super admin: (severity, organizationId, applicationId, assessmentId). */
    private List<RemediationRowDto> filtered(String severity, String orgFilter, String appFilter, String asmtFilter) {
        return service.list(null, one(severity), one(orgFilter), one(appFilter), one(asmtFilter), null, null, null, false, PAGE, superAdmin()).getContent();
    }

    /** A single filter value as the service's list form; null stays "no filter". */
    private static List<String> one(String value) {
        return value == null ? null : List.of(value);
    }

    /** Filtered list as a super admin, by row type ("VULNERABILITY" / "RETEST"). */
    private List<RemediationRowDto> byType(String type) {
        return service.list(null, null, null, null, null, null, type, null, false, PAGE, superAdmin()).getContent();
    }

    /** Filtered list as a super admin, by vulnerability status. */
    private List<RemediationRowDto> byStatus(String... statuses) {
        return service.list(null, null, null, null, null, List.of(statuses), null, null, false, PAGE, superAdmin()).getContent();
    }

    // ── Completed retests (opt-in) ───────────────────────────────────────────────

    /** Queue as a super admin with verified retests included. */
    private List<RemediationRowDto> withCompletedRetests() {
        return service.list(null, null, null, null, null, null, null, null, true, PAGE, superAdmin()).getContent();
    }

    @Test
    void completedRetestsAreHiddenByDefault() {
        retest("OpenRetest", "IN_PROGRESS", -10, -5);
        retest("PassedRetest", "PASSED", -20, -15);
        retest("FailedRetest", "FAILED", -20, -15);

        assertThat(names(list())).containsExactly("OpenRetest");
    }

    @Test
    void completedRetestsAppearWhenAskedFor() {
        retest("OpenRetest", "IN_PROGRESS", -10, -5);
        retest("PassedRetest", "PASSED", -20, -15);
        retest("FailedRetest", "FAILED", -20, -15);

        assertThat(names(withCompletedRetests()))
                .containsExactlyInAnyOrder("OpenRetest", "PassedRetest", "FailedRetest");
    }

    @Test
    void aVerifiedRetestIsNeverUrgent_howeverLongAgoItWasDue() {
        // Its scheduled end is long past, but it was checked — flagging it as overdue work
        // would be a standing false alarm in a view meant to show what still needs doing.
        retest("PassedRetest", "PASSED", -60, -50);

        var row = row(withCompletedRetests(), "PassedRetest");
        assertThat(row.isUrgent()).isFalse();
        assertThat(row.isWarning()).isFalse();
        assertThat(row.getRetestStatus()).isEqualTo("PASSED");
    }

    @Test
    void openRetestsStayUrgentWhenCompletedOnesAreIncluded() {
        retest("OverdueOpen", "IN_PROGRESS", -10, -5);

        assertThat(row(withCompletedRetests(), "OverdueOpen").isUrgent()).isTrue();
    }

    @Test
    void cancelledRetestsStayOutEvenWhenCompletedOnesAreIncluded() {
        // Cancelled is neither outstanding work nor a result worth reporting on.
        retest("CancelledRetest", "CANCELLED", -20, -15);

        assertThat(names(withCompletedRetests())).doesNotContain("CancelledRetest");
    }

    @Test
    void includingCompletedRetestsDoesNotWidenTheVulnerabilityHalf() {
        // A closed finding is not a queue row, whatever the retest toggle says.
        var closed = freshVuln("ClosedVuln");
        var v = vulnerabilityRepository.findById(closed).orElseThrow();
        v.setStatus("Closed");
        v.setClosedAt(LocalDateTime.now());
        vulnerabilityRepository.save(v);

        assertThat(names(withCompletedRetests())).doesNotContain("ClosedVuln");
    }

    @Test
    void theCompletedCountIsPagedCorrectly() {
        // The flag lives inside the UNION branch, so it has to be bound on the count query too —
        // otherwise the total disagrees with the rows.
        retest("OpenRetest", "IN_PROGRESS", -10, -5);
        retest("PassedRetest", "PASSED", -20, -15);

        var page = service.list(null, null, null, null, null, null, null, null, true,
                PageRequest.of(0, 1), superAdmin());
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(1);
    }

    @Test
    void everyRowSaysWhichWorkflowItsAssessmentIsOn() {
        String secondAssessment = assessment(orgId, appId, "PCI Review");
        assessmentRepository.findById(secondAssessment).ifPresent(a -> {
            a.setWorkflowId(TestWorkflows.SECOND_ID);
            assessmentRepository.save(a);
        });
        vuln("On default", VulnerabilitySeverity.CRITICAL, 30);
        vulnBuilder("On second", VulnerabilitySeverity.CRITICAL, 30).assessment(secondAssessment).save();

        var rows = list();

        assertThat(row(rows, "On default").getWorkflowId()).isEqualTo(AssessmentWorkflow.DEFAULT_ID);
        assertThat(row(rows, "On second").getWorkflowId()).isEqualTo(TestWorkflows.SECOND_ID);
    }

    @Test
    void retestRowsSayItToo() {
        retest("Retest target", "SCHEDULED", -2, 5);

        var rows = withCompletedRetests();

        assertThat(row(rows, "Retest target").getWorkflowId()).isEqualTo(AssessmentWorkflow.DEFAULT_ID);
    }

    // ── Interleaving + ordering (the crux) ───────────────────────────────────────

    @Test
    void interleaves_urgentThenWarning_thenNotYetDue_byDueDateWithinTier() {
        vuln("UrgentVuln", VulnerabilitySeverity.HIGH, 40);   // due today-10 → urgent
        vuln("WarningVuln", VulnerabilitySeverity.HIGH, 20);  // due today+10 → warning
        retest("UrgentRetest", "IN_PROGRESS", -10, -5);       // end today-5 → urgent
        retest("FutureRetest", "SCHEDULED", 10, 30);          // end today+30 → not-yet-due
        retest("RequestedRetest", "REQUESTED", null, null);   // no schedule → not-yet-due, null due (last)

        var result = list();

        // tier0: UrgentVuln (due-10) before UrgentRetest (due-5); tier1: WarningVuln;
        // tier2: FutureRetest (due+30) before RequestedRetest (null due, last).
        assertThat(result).extracting(RemediationRowDto::getVulnerabilityName)
                .containsExactly("UrgentVuln", "UrgentRetest", "WarningVuln", "FutureRetest", "RequestedRetest");
        assertThat(result).extracting(RemediationRowDto::getType)
                .containsExactly("VULNERABILITY", "RETEST", "VULNERABILITY", "RETEST", "RETEST");
    }

    /**
     * The safety net for the retest-lookup restructuring (Task 2 of the queue-performance phase):
     * pins {@code listRemediationDue}'s full row content — every field {@code toRemediationRow} reads
     * off the native query's {@code Object[]} by hardcoded index — for a known ordering that mixes a
     * never-retested urgent vuln, an urgent open retest, and a warning-tier vuln with a history of
     * completed retests (so {@code last_retest_status}/{@code last_retest_date} are exercised, not just
     * left null). A restructuring that changes the outer projection's column order/arity without
     * updating the mapper in lockstep, or that resolves the retest lookup incorrectly once deferred to
     * only the surviving page, shows up here.
     */
    @Test
    void pinsFullRowContentAndOrdering_includingLastRetestColumns() {
        String urgentVulnId = vuln("UrgentVuln", VulnerabilitySeverity.HIGH, 40); // HIGH due at 30 → due today-10
        String warningVulnId = vuln("WarningVulnWithRetests", VulnerabilitySeverity.HIGH, 20); // due today+10

        LocalDateTime olderClosed = LocalDateTime.now().minusDays(10).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime newerClosed = LocalDateTime.now().minusDays(1).truncatedTo(ChronoUnit.MICROS);
        retestRepository.save(baseRetest(warningVulnId, "PASSED", assessmentId, appId)
                .closedDate(olderClosed).updatedAt(olderClosed).build());
        retestRepository.save(baseRetest(warningVulnId, "FAILED", assessmentId, appId)
                .closedDate(newerClosed).updatedAt(newerClosed).build());

        String urgentRetestVulnId = freshVuln("UrgentRetestTarget");
        LocalDateTime retestStart = LocalDateTime.now().minusDays(10).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime retestEnd = LocalDateTime.now().minusDays(5).truncatedTo(ChronoUnit.MICROS);
        Retest urgentRetest = retestRepository.save(baseRetest(urgentRetestVulnId, "IN_PROGRESS", assessmentId, appId)
                .scheduledStartDate(retestStart).scheduledEndDate(retestEnd).build());

        Vulnerability urgentVuln = vulnerabilityRepository.findById(urgentVulnId).orElseThrow();
        Vulnerability warningVuln = vulnerabilityRepository.findById(warningVulnId).orElseThrow();

        Page<RemediationDueRow> page = vulnerabilityRepository.listRemediationDue(
                RemediationQueueCriteria.builder().build(), PAGE);

        assertThat(page.getTotalElements()).isEqualTo(3);
        List<RemediationDueRow> rows = page.getContent();
        // tier0 (urgent), due_date ascending: UrgentVuln (today-10) before the open retest (today-5);
        // tier1 (warning): WarningVulnWithRetests (today+10).
        assertThat(rows).extracting(RemediationDueRow::name)
                .containsExactly("UrgentVuln", "UrgentRetestTarget", "WarningVulnWithRetests");

        RemediationDueRow r0 = rows.get(0);
        assertThat(r0.rowType()).isEqualTo("VULNERABILITY");
        assertThat(r0.rowId()).isEqualTo(urgentVulnId);
        assertThat(r0.vulnerabilityId()).isEqualTo(urgentVulnId);
        assertThat(r0.assessmentId()).isEqualTo(assessmentId);
        assertThat(r0.workflowId()).isEqualTo(AssessmentWorkflow.DEFAULT_ID);
        assertThat(r0.applicationId()).isEqualTo(appId);
        assertThat(r0.applicationName()).isEqualTo("Payments API");
        assertThat(r0.organizationId()).isEqualTo(orgId);
        assertThat(r0.organizationName()).isEqualTo("Acme");
        assertThat(r0.name()).isEqualTo("UrgentVuln");
        assertThat(r0.severity()).isEqualTo(VulnerabilitySeverity.HIGH.ordinal());
        assertThat(r0.urgent()).isTrue();
        assertThat(r0.warning()).isFalse();
        assertThat(r0.dueDate()).isEqualTo(urgentVuln.getDueAt().toLocalDate());
        assertThat(r0.startDate()).isNull();
        assertThat(r0.endDate()).isNull();
        assertThat(r0.vulnerabilityStatus()).isEqualTo("Open");
        assertThat(r0.retestStatus()).isNull();
        assertThat(r0.lastRetestStatus()).isNull();
        assertThat(r0.lastRetestDate()).isNull();

        RemediationDueRow r1 = rows.get(1);
        assertThat(r1.rowType()).isEqualTo("RETEST");
        assertThat(r1.rowId()).isEqualTo(urgentRetest.getId());
        assertThat(r1.vulnerabilityId()).isEqualTo(urgentRetestVulnId);
        assertThat(r1.assessmentId()).isEqualTo(assessmentId);
        assertThat(r1.workflowId()).isEqualTo(AssessmentWorkflow.DEFAULT_ID);
        assertThat(r1.applicationId()).isEqualTo(appId);
        assertThat(r1.applicationName()).isEqualTo("Payments API");
        assertThat(r1.organizationId()).isEqualTo(orgId);
        assertThat(r1.organizationName()).isEqualTo("Acme");
        assertThat(r1.name()).isEqualTo("UrgentRetestTarget");
        assertThat(r1.severity()).isEqualTo(VulnerabilitySeverity.HIGH.ordinal());
        assertThat(r1.urgent()).isTrue();
        assertThat(r1.warning()).isFalse();
        assertThat(r1.dueDate()).isEqualTo(retestEnd.toLocalDate());
        assertThat(r1.startDate()).isEqualTo(retestStart);
        assertThat(r1.endDate()).isEqualTo(retestEnd);
        assertThat(r1.vulnerabilityStatus()).isEqualTo("Open");
        assertThat(r1.retestStatus()).isEqualTo("IN_PROGRESS");
        assertThat(r1.lastRetestStatus()).isNull();
        assertThat(r1.lastRetestDate()).isNull();

        RemediationDueRow r2 = rows.get(2);
        assertThat(r2.rowType()).isEqualTo("VULNERABILITY");
        assertThat(r2.rowId()).isEqualTo(warningVulnId);
        assertThat(r2.vulnerabilityId()).isEqualTo(warningVulnId);
        assertThat(r2.assessmentId()).isEqualTo(assessmentId);
        assertThat(r2.workflowId()).isEqualTo(AssessmentWorkflow.DEFAULT_ID);
        assertThat(r2.applicationId()).isEqualTo(appId);
        assertThat(r2.applicationName()).isEqualTo("Payments API");
        assertThat(r2.organizationId()).isEqualTo(orgId);
        assertThat(r2.organizationName()).isEqualTo("Acme");
        assertThat(r2.name()).isEqualTo("WarningVulnWithRetests");
        assertThat(r2.severity()).isEqualTo(VulnerabilitySeverity.HIGH.ordinal());
        assertThat(r2.urgent()).isFalse();
        assertThat(r2.warning()).isTrue();
        assertThat(r2.dueDate()).isEqualTo(warningVuln.getDueAt().toLocalDate());
        assertThat(r2.startDate()).isNull();
        assertThat(r2.endDate()).isNull();
        assertThat(r2.vulnerabilityStatus()).isEqualTo("Open");
        assertThat(r2.retestStatus()).isNull();
        // The later-updated (FAILED) retest wins for both the result and its date.
        assertThat(r2.lastRetestStatus()).isEqualTo("FAILED");
        assertThat(r2.lastRetestDate()).isEqualTo(newerClosed);
    }

    /**
     * Guards the deferred lookup's {@code page.row_type = 'VULNERABILITY'} gate specifically — the
     * shape the other tests can't catch. A RETEST-branch row's {@code vulnerability_id} points at the
     * vuln under retest, not at itself, so without that gate the deferred outer LATERAL (keyed only on
     * {@code vulnerability_id}) would resolve the *same* vuln's last verified retest onto the retest
     * row too. Every other test's open retest carries no completed (PASSED/FAILED) retest of its own,
     * so the LATERAL's own {@code status IN ('PASSED','FAILED')} filter already excludes it — the gate
     * being absent would look identical. Here the same vulnerability carries both a completed retest
     * and a separate open one, so a missing gate has something to leak.
     */
    @Test
    void retestBranchRow_neverCarriesLastRetestColumns_evenWhenItsOwnVulnHasACompletedRetestToo() {
        String vulnId = vuln("DoubleRetestVuln", VulnerabilitySeverity.HIGH, 20); // due today+10 -> warning
        LocalDateTime completedClosed = LocalDateTime.now().minusDays(3).truncatedTo(ChronoUnit.MICROS);
        retestRepository.save(baseRetest(vulnId, "FAILED", assessmentId, appId)
                .closedDate(completedClosed).updatedAt(completedClosed).build());
        Retest openRetest = retestRepository.save(baseRetest(vulnId, "SCHEDULED", assessmentId, appId)
                .scheduledEndDate(LocalDateTime.now().plusDays(5)).build());

        List<RemediationDueRow> rows = vulnerabilityRepository.listRemediationDue(
                RemediationQueueCriteria.builder().build(), PAGE).getContent();

        RemediationDueRow vulnRow = rows.stream()
                .filter(r -> "VULNERABILITY".equals(r.rowType()) && vulnId.equals(r.rowId()))
                .findFirst().orElseThrow();
        RemediationDueRow retestRow = rows.stream()
                .filter(r -> "RETEST".equals(r.rowType()) && openRetest.getId().equals(r.rowId()))
                .findFirst().orElseThrow();

        assertThat(vulnRow.lastRetestStatus()).isEqualTo("FAILED");
        assertThat(vulnRow.lastRetestDate()).isEqualTo(completedClosed);
        // The regression this guards: without the row_type gate, the retest row would pick up the
        // same FAILED/completedClosed values the VULNERABILITY row above just asserted.
        assertThat(retestRow.lastRetestStatus()).isNull();
        assertThat(retestRow.lastRetestDate()).isNull();
    }

    /**
     * Pins the queue's contract for a deep page: a page taken at any offset must return exactly the
     * same rows, in the same order, as the equivalent slice of an unpaged call. 400 rows span both
     * branches and all three tiers, sized so tier boundaries land on page boundaries: tier0
     * (urgentVuln 150 + urgentRetest 50 = 200 rows, global ranks 0-199) is mixed across both branches,
     * but tier1 (warningVuln, 100 rows, global ranks 200-299) comes from the vulnerability branch
     * alone — so the deep page below (offset 200, size 50) sits entirely inside the warning tier and
     * entirely inside one branch.
     *
     * <p>The fixtures are sized this way because an earlier per-branch-limiting implementation (a
     * "fence" subquery with its own {@code LIMIT}, taken as {@code pageSize} instead of
     * {@code offset + pageSize}) was built, measured and reverted — that shape would have returned
     * only the vulnerability branch's top 50 locally-sorted (most-urgent) rows and never reached the
     * warning tier at all. See {@code docs/superpowers/notes/2026-09-16-workflows-carry-forward.md}.
     */
    @Test
    void pagesDeepOffsetsCorrectly_matchingTheUnpagedOrder() {
        for (int i = 0; i < 150; i++) {
            vuln("urgentVuln-" + i, VulnerabilitySeverity.HIGH, 40 + (i % 5)); // due today-(10..14) -> urgent
        }
        for (int i = 0; i < 50; i++) {
            retest("urgentRetest-" + i, "IN_PROGRESS", -20, -(5 + (i % 5))); // overdue -> urgent
        }
        for (int i = 0; i < 100; i++) {
            vuln("warningVuln-" + i, VulnerabilitySeverity.HIGH, 20 + (i % 5)); // due today+(6..10) -> warning
        }
        for (int i = 0; i < 100; i++) {
            retest("futureRetest-" + i, "SCHEDULED", 5, 20 + (i % 5)); // not yet due -> tier 2
        }

        List<RemediationDueRow> all = vulnerabilityRepository.listRemediationDue(
                RemediationQueueCriteria.builder().build(), Pageable.unpaged()).getContent();
        assertThat(all).hasSize(400);

        List<RemediationDueRow> firstPage = vulnerabilityRepository.listRemediationDue(
                RemediationQueueCriteria.builder().build(), PageRequest.of(0, 50)).getContent();
        assertThat(firstPage).containsExactlyElementsOf(all.subList(0, 50));

        List<RemediationDueRow> deepPage = vulnerabilityRepository.listRemediationDue(
                RemediationQueueCriteria.builder().build(), PageRequest.of(4, 50)).getContent();
        assertThat(deepPage).containsExactlyElementsOf(all.subList(200, 250));
        assertThat(deepPage).extracting(RemediationDueRow::rowType).containsOnly("VULNERABILITY");
    }

    /** Pins the final tiebreaker: rows sharing the same tier and the same stored due date must come
     *  back in {@code row_id} ascending order, not whatever order Postgres happens to produce. */
    @Test
    void tiesOnDueDate_areBrokenByRowIdAscending() {
        String a = vuln("TiedA", VulnerabilitySeverity.HIGH, 40);
        String b = vuln("TiedB", VulnerabilitySeverity.HIGH, 40);
        String c = vuln("TiedC", VulnerabilitySeverity.HIGH, 40);

        List<RemediationDueRow> rows = vulnerabilityRepository.listRemediationDue(
                RemediationQueueCriteria.builder().build(), PAGE).getContent();
        List<String> tiedInOrder = rows.stream().map(RemediationDueRow::rowId)
                .filter(id -> id.equals(a) || id.equals(b) || id.equals(c)).toList();

        assertThat(tiedInOrder).containsExactlyElementsOf(
                java.util.stream.Stream.of(a, b, c).sorted().toList());
    }

    /**
     * Pins the other half of the queue's contract: an unpaged call must never be capped. The CSV
     * export calls {@code listRemediationDue} with {@code Pageable.unpaged()} specifically so it can
     * never come back truncated — 200 rows here, well past the default 50-row page size.
     *
     * <p>The fixture is sized past that default because an earlier per-branch-limiting
     * implementation (see the note on {@link #pagesDeepOffsetsCorrectly_matchingTheUnpagedOrder})
     * would have had nothing to derive a per-branch limit from on an unpaged {@code Pageable} (no
     * offset/pageSize), and this pins that a bogus derived limit does not silently reappear and cap
     * the result. See {@code docs/superpowers/notes/2026-09-16-workflows-carry-forward.md}.
     */
    @Test
    void unpagedCall_returnsEveryMatchingRow() {
        for (int i = 0; i < 120; i++) {
            vuln("unpagedVuln-" + i, VulnerabilitySeverity.HIGH, 40);
        }
        for (int i = 0; i < 80; i++) {
            retest("unpagedRetest-" + i, "IN_PROGRESS", -20, -5);
        }

        Page<RemediationDueRow> page = vulnerabilityRepository.listRemediationDue(
                RemediationQueueCriteria.builder().build(), Pageable.unpaged());

        assertThat(page.getContent()).hasSize(200);
        assertThat(page.getTotalElements()).isEqualTo(200);
    }

    @Test
    void classifiesUrgentAndWarningFlags() {
        vuln("Urgent", VulnerabilitySeverity.HIGH, 40);
        vuln("Warning", VulnerabilitySeverity.HIGH, 20);
        retest("UrgentR", "IN_PROGRESS", -10, -5);
        retest("FutureR", "SCHEDULED", 10, 30);

        var byName = list();
        assertThat(row(byName, "Urgent").isUrgent()).isTrue();
        assertThat(row(byName, "Urgent").isWarning()).isFalse();
        assertThat(row(byName, "Warning").isWarning()).isTrue();
        assertThat(row(byName, "Warning").isUrgent()).isFalse();
        assertThat(row(byName, "UrgentR").isUrgent()).isTrue();
        assertThat(row(byName, "FutureR").isUrgent()).isFalse();
        assertThat(row(byName, "FutureR").isWarning()).isFalse();  // retests are never "warning"
    }

    // ── Vulnerability warning-window boundary ────────────────────────────────────

    @Test
    void includesAtWarningThreshold_excludesFresh() {
        vuln("At", VulnerabilitySeverity.HIGH, 15);   // exactly HIGH's 15-day threshold → in
        vuln("Below", VulnerabilitySeverity.HIGH, 14); // one short → out
        assertThat(names(list())).containsExactly("At");
    }

    // ── Vulnerability exclusions ──────────────────────────────────────────────────

    @Test
    void excludesClosedExceptionUntrackedUnopenedSoftDeletedAndDeletedAssessment() {
        vuln("Kept", VulnerabilitySeverity.HIGH, 40);
        vulnBuilder("Closed", VulnerabilitySeverity.HIGH, 40).status("Closed").save();
        vulnBuilder("Exception", VulnerabilitySeverity.HIGH, 40).status("Exception").save();
        vuln("Informational", VulnerabilitySeverity.INFORMATIONAL, 400); // no SLA
        vulnBuilder("Unopened", VulnerabilitySeverity.HIGH, 40).openedAtNull().save();
        vulnBuilder("SoftDeleted", VulnerabilitySeverity.HIGH, 40).softDeleted().save();

        var goneAssessment = deletedAssessment(orgId, appId);
        vulnBuilder("OnDeletedAssessment", VulnerabilitySeverity.HIGH, 40).assessment(goneAssessment).save();

        assertThat(names(list())).containsExactly("Kept");
    }

    // ── Retest exclusions ─────────────────────────────────────────────────────────

    @Test
    void includesOnlyOpenRetests() {
        retest("Requested", "REQUESTED", null, null);
        retest("Scheduled", "SCHEDULED", 5, 10);
        retest("InProgress", "IN_PROGRESS", -1, 5);
        retest("Passed", "PASSED", -1, 5);
        retest("Failed", "FAILED", -1, 5);
        retest("Cancelled", "CANCELLED", -1, 5);

        assertThat(names(list())).containsExactlyInAnyOrder("Requested", "Scheduled", "InProgress");
    }

    @Test
    void excludesSoftDeletedRetestsAndDeletedAssessmentRetests() {
        retest("Kept", "SCHEDULED", 5, 10);

        var v = freshVuln("SoftDeletedRetestVuln");
        retestRepository.save(baseRetest(v, "SCHEDULED", assessmentId, appId)
                .deletedAt(LocalDateTime.now()).build());

        var goneAssessment = deletedAssessment(orgId, appId);
        var v2 = freshVuln("GoneAsmtRetestVuln");
        retestRepository.save(baseRetest(v2, "SCHEDULED", goneAssessment, appId).build());

        assertThat(names(list())).containsExactly("Kept");
    }

    // ── CSV export ───────────────────────────────────────────────────────────────

    private String exportCsv(boolean includeCompletedRetests) {
        return service.exportCsv(null, null, null, null, null, null, null, null,
                includeCompletedRetests, Sort.unsorted(), superAdmin());
    }

    @Test
    void exportCsv_writesHeaderAndOneRowPerQueueItem() {
        vuln("SQL Injection", VulnerabilitySeverity.HIGH, 40);

        var lines = exportCsv(false).split("\n");

        assertThat(lines[0]).isEqualTo("Type,Vulnerability,Severity,Status,Application,Organization,"
                + "Due Date,Scheduled Start,Scheduled End,Retest Status,Last Retest,Last Retest Date,"
                + "Completed Date,Result,Completed By");
        assertThat(lines).hasSize(2);
        assertThat(lines[1]).contains("VULNERABILITY", "SQL Injection", "HIGH", "Payments API", "Acme");
    }

    @Test
    void exportCsv_carriesRetestCompletionColumns() {
        // The point of the export: when a retest closed, how it went, and who signed off — none of
        // which the queue's union query (or the table) carries.
        var v = freshVuln("Retested");
        // Truncated to microseconds: Postgres stores no finer, so an untruncated now() comes back
        // rounded and the assertion below compares two different strings on a nanosecond clock.
        LocalDateTime closed = LocalDateTime.now().minusDays(3).truncatedTo(ChronoUnit.MICROS);
        retestRepository.save(baseRetest(v, "PASSED", assessmentId, appId)
                .closedDate(closed).result("PASS").completedBy("alice").build());

        var lines = exportCsv(true).split("\n");

        assertThat(lines).hasSize(2);
        assertThat(lines[1]).contains("RETEST", "PASSED", closed.toString(), "PASS", "alice");
    }

    @Test
    void exportCsv_leavesCompletionColumnsBlankForVulnerabilityRows() {
        vuln("OpenFinding", VulnerabilitySeverity.HIGH, 40);

        var row = exportCsv(false).split("\n")[1];

        // Trailing ",," — the three completion columns are empty on a non-retest row.
        assertThat(row).endsWith(",,,");
    }

    @Test
    void exportCsv_honoursIncludeCompletedRetests() {
        retest("OpenRetest", "IN_PROGRESS", -10, -5);
        retest("PassedRetest", "PASSED", -20, -15);

        assertThat(exportCsv(false)).contains("OpenRetest").doesNotContain("PassedRetest");
        assertThat(exportCsv(true)).contains("OpenRetest").contains("PassedRetest");
    }

    @Test
    void exportCsv_isNotCappedByTheListPageSize() {
        for (int i = 0; i < 60; i++) {
            vuln("vuln-" + i, VulnerabilitySeverity.HIGH, 40);
        }

        // 60 rows > the 50-row PAGE the list tests use: a capped export would hand back a
        // truncated file that still looks complete.
        assertThat(exportCsv(false).split("\n")).hasSize(61);
    }

    @Test
    void exportCsv_quotesValuesCarryingCommasAndQuotes() {
        vuln("XSS, stored \"reflected\"", VulnerabilitySeverity.HIGH, 40);

        assertThat(exportCsv(false)).contains("\"XSS, stored \"\"reflected\"\"\"");
    }

    @Test
    void exportCsv_isScopedToWhatTheCallerMayRead() {
        vuln("Mine", VulnerabilitySeverity.HIGH, 40);
        var orgB = organization("Globex").getId();
        var appB = application(orgB, "Ledger").getId();
        var asmtB = assessment(orgB, appB, "B");
        vulnBuilder("Theirs", VulnerabilitySeverity.HIGH, 40).assessment(asmtB).save();
        user("acme-user", orgId);

        var csv = service.exportCsv(null, null, null, null, null, null, null, null, false, Sort.unsorted(),
                auth("acme-user", Permission.VULNERABILITIES_READ_ORG.getPermission()));

        assertThat(csv).contains("Mine").doesNotContain("Theirs");
    }

    // ── Enrichment + last-retest overlay ─────────────────────────────────────────

    @Test
    void retestWithSoftDeletedVuln_stillListedButHidesVulnNameAndSeverity() {
        // A retest can stay open after its vuln is soft-deleted. The row still appears (it's actionable),
        // but the deleted vuln's name/severity/status are not surfaced — null, so the UI falls back to
        // the id (and renders the status as "None").
        var v = vulnBuilder("DeletedVuln", VulnerabilitySeverity.HIGH, 0).softDeleted().save();
        retestRepository.save(baseRetest(v, "SCHEDULED", assessmentId, appId)
                .scheduledEndDate(LocalDateTime.now().plusDays(5)).build());

        var result = list();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getType()).isEqualTo("RETEST");
        assertThat(result.get(0).getVulnerabilityName()).isNull();
        assertThat(result.get(0).getSeverity()).isNull();
        assertThat(result.get(0).getVulnerabilityStatus()).isNull();
    }

    @Test
    void enrichesJoinedNames() {
        vuln("V", VulnerabilitySeverity.HIGH, 40);
        var dto = row(list(), "V");
        assertThat(dto.getApplicationName()).isEqualTo("Payments API");
        assertThat(dto.getOrganizationName()).isEqualTo("Acme");
        assertThat(dto.getApplicationId()).isEqualTo(appId);
        assertThat(dto.getOrganizationId()).isEqualTo(orgId);
    }

    @Test
    void overlaysMostRecentPassedOrFailedRetestOnVulnRows() {
        var v = vuln("V", VulnerabilitySeverity.HIGH, 20);
        // Two completed retests on V — the later (FAILED) wins; completed retests are not queue rows.
        retestRepository.save(baseRetest(v, "PASSED", assessmentId, appId)
                .updatedAt(LocalDateTime.now().minusDays(10)).build());
        retestRepository.save(baseRetest(v, "FAILED", assessmentId, appId)
                .updatedAt(LocalDateTime.now().minusDays(1)).build());

        var result = list();
        assertThat(result).hasSize(1);
        assertThat(row(result, "V").getLastRetestStatus()).isEqualTo("FAILED");
    }

    @Test
    void sortsByLastRetestResult_withNeverRetestedLast() {
        // Opened 20/21/22 days ago, so the default due-date order is the reverse of the sorted one.
        var passed = vuln("PassedVuln", VulnerabilitySeverity.HIGH, 20);
        var failed = vuln("FailedVuln", VulnerabilitySeverity.HIGH, 21);
        vuln("NeverRetested", VulnerabilitySeverity.HIGH, 22);
        retestRepository.save(baseRetest(passed, "PASSED", assessmentId, appId)
                .updatedAt(LocalDateTime.now().minusDays(2)).build());
        retestRepository.save(baseRetest(failed, "FAILED", assessmentId, appId)
                .updatedAt(LocalDateTime.now().minusDays(2)).build());

        var ascending = service.list(null, null, null, null, null, null, null, null, false,
                PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "lastRetestStatus")), superAdmin()).getContent();
        assertThat(names(ascending)).containsExactly("FailedVuln", "PassedVuln", "NeverRetested");

        var descending = service.list(null, null, null, null, null, null, null, null, false,
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "lastRetestStatus")), superAdmin()).getContent();
        assertThat(names(descending)).containsExactly("PassedVuln", "FailedVuln", "NeverRetested");
    }

    @Test
    void carriesTheLastRetestDate_fromTheSameRetestAsTheResult_andSortsByIt() {
        var older = vuln("OlderRetest", VulnerabilitySeverity.HIGH, 20);
        var newer = vuln("NewerRetest", VulnerabilitySeverity.HIGH, 21);
        vuln("NeverRetested", VulnerabilitySeverity.HIGH, 22);
        LocalDateTime olderClosed = LocalDateTime.now().minusDays(9).withNano(0);
        LocalDateTime newerClosed = LocalDateTime.now().minusDays(1).withNano(0);
        // Two retests on OlderRetest: the later-updated one decides both the result and the date.
        retestRepository.save(baseRetest(older, "PASSED", assessmentId, appId)
                .closedDate(LocalDateTime.now().minusDays(30).withNano(0))
                .updatedAt(LocalDateTime.now().minusDays(30)).build());
        retestRepository.save(baseRetest(older, "FAILED", assessmentId, appId)
                .closedDate(olderClosed).updatedAt(LocalDateTime.now().minusDays(9)).build());
        retestRepository.save(baseRetest(newer, "PASSED", assessmentId, appId)
                .closedDate(newerClosed).updatedAt(LocalDateTime.now().minusDays(1)).build());

        var rows = list();
        assertThat(row(rows, "OlderRetest").getLastRetestStatus()).isEqualTo("FAILED");
        assertThat(row(rows, "OlderRetest").getLastRetestDate()).isEqualTo(olderClosed);
        assertThat(row(rows, "NewerRetest").getLastRetestDate()).isEqualTo(newerClosed);
        assertThat(row(rows, "NeverRetested").getLastRetestDate()).isNull();

        // Newest first; never-retested last. The default due-date order would put NeverRetested first.
        var newestFirst = service.list(null, null, null, null, null, null, null, null, false,
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "lastRetestDate")), superAdmin()).getContent();
        assertThat(names(newestFirst)).containsExactly("NewerRetest", "OlderRetest", "NeverRetested");
    }

    @Test
    void bothRowTypesCarryTheUnderlyingVulnerabilityStatus() {
        // The table's Status column always shows the vulnerability's status — on a retest row that's
        // the status of the vuln being retested, not the retest's own REQUESTED/SCHEDULED/IN_PROGRESS.
        vulnBuilder("V", VulnerabilitySeverity.HIGH, 40).status("Past Due").save();
        retest("R", "SCHEDULED", 5, 10); // its underlying vuln is seeded with status "Open"

        assertThat(row(list(), "V").getVulnerabilityStatus()).isEqualTo("Past Due");
        assertThat(row(list(), "V").getRetestStatus()).isNull();
        assertThat(row(list(), "R").getVulnerabilityStatus()).isEqualTo("Open");
        assertThat(row(list(), "R").getRetestStatus()).isEqualTo("SCHEDULED");
        assertThat(row(list(), "R").getLastRetestStatus()).isNull();
    }

    // ── Search ────────────────────────────────────────────────────────────────────

    @Test
    void searchesNameApplicationOrganizationAndType() {
        vuln("SQLInjection", VulnerabilitySeverity.HIGH, 40);
        retest("XSSRetest", "SCHEDULED", 5, 10);

        assertThat(names(list("sqlinjection", superAdmin()))).containsExactly("SQLInjection");
        assertThat(names(list("payments", superAdmin()))).containsExactlyInAnyOrder("SQLInjection", "XSSRetest");
        assertThat(names(list("acme", superAdmin()))).containsExactlyInAnyOrder("SQLInjection", "XSSRetest");
        assertThat(names(list("retest", superAdmin()))).containsExactly("XSSRetest"); // matches row type
        assertThat(list("nomatch", superAdmin())).isEmpty();
    }

    // ── Header filters (severity / organization / application / assessment) ───────

    @Test
    void filtersBySeverity_acrossBothRowTypes() {
        vuln("HighVuln", VulnerabilitySeverity.HIGH, 40);
        vuln("CriticalVuln", VulnerabilitySeverity.CRITICAL, 40);
        retest("HighRetest", "SCHEDULED", 5, 10); // its vuln is seeded HIGH

        assertThat(names(filtered("HIGH", null, null, null)))
                .containsExactlyInAnyOrder("HighVuln", "HighRetest");
        assertThat(names(filtered("CRITICAL", null, null, null))).containsExactly("CriticalVuln");
        assertThat(names(filtered("critical", null, null, null))).containsExactly("CriticalVuln"); // case-insensitive
        assertThat(names(filtered("LOW", null, null, null))).isEmpty();
        // An unparseable severity is ignored rather than matching nothing (mirrors the vulns list).
        assertThat(names(filtered("NOT_A_SEVERITY", null, null, null))).hasSize(3);
    }

    @Test
    void filtersByOrganizationApplicationAndAssessment() {
        vuln("inAcme", VulnerabilitySeverity.HIGH, 40); // Acme / Payments API / Q3 Pentest

        var otherAppId = application(orgId, "Ledger").getId();
        var otherAsmt = assessment(orgId, otherAppId, "Q4 Pentest");
        vulnBuilder("inLedger", VulnerabilitySeverity.HIGH, 40).assessment(otherAsmt).save();

        var orgB = organization("Globex").getId();
        var appB = application(orgB, "Billing").getId();
        var asmtB = assessment(orgB, appB, "B");
        vulnBuilder("inGlobex", VulnerabilitySeverity.HIGH, 40).assessment(asmtB).save();

        assertThat(names(filtered(null, orgId, null, null))).containsExactlyInAnyOrder("inAcme", "inLedger");
        assertThat(names(filtered(null, orgB, null, null))).containsExactly("inGlobex");
        assertThat(names(filtered(null, null, appId, null))).containsExactly("inAcme");
        assertThat(names(filtered(null, null, null, otherAsmt))).containsExactly("inLedger");
        // Filters combine (AND): an app outside the filtered org yields nothing.
        assertThat(names(filtered(null, orgB, appId, null))).isEmpty();
    }

    @Test
    void filtersCannotWidenAScopedCallersSlice() {
        vuln("inAcme", VulnerabilitySeverity.HIGH, 40);
        var orgB = organization("Globex").getId();
        var appB = application(orgB, "Ledger").getId();
        var asmtB = assessment(orgB, appB, "B");
        vulnBuilder("inGlobex", VulnerabilitySeverity.HIGH, 40).assessment(asmtB).save();

        user("acme-user", orgId);
        var acmeAuth = auth("acme-user", Permission.VULNERABILITIES_READ_ORG.getPermission());

        // Asking for another org as an org-scoped caller returns nothing, not the other org's rows.
        assertThat(service.list(null, null, List.of(orgB), null, null, null, null, null, false, PAGE, acmeAuth).getContent()).isEmpty();
        // Asking for their own org still works.
        assertThat(names(service.list(null, null, List.of(orgId), null, null, null, null, null, false, PAGE, acmeAuth).getContent()))
                .containsExactly("inAcme");
    }

    @Test
    void ownedScopedUser_cannotFilterToAnUnownedApplication() {
        var owner = user("owner", orgId);
        var ownedAppId = ownedApp(orgId, "Owned", owner.getId()).getId();
        var ownedAsmt = assessment(orgId, ownedAppId, "O");
        vulnBuilder("inOwned", VulnerabilitySeverity.HIGH, 40).assessment(ownedAsmt).save();
        vuln("inOther", VulnerabilitySeverity.HIGH, 40); // default app, not owned

        var ownerAuth = auth("owner", Permission.VULNERABILITIES_READ_OWNED.getPermission());
        assertThat(service.list(null, null, null, List.of(appId), null, null, null, null, false, PAGE, ownerAuth).getContent()).isEmpty();
        assertThat(names(service.list(null, null, null, List.of(ownedAppId), null, null, null, null, false, PAGE, ownerAuth).getContent()))
                .containsExactly("inOwned");
    }

    @Test
    void ownedScopedUser_severalApplications_narrowToTheOwnedOnes() {
        var owner = user("owner", orgId);
        var ownedAppId = ownedApp(orgId, "Owned", owner.getId()).getId();
        var ownedAsmt = assessment(orgId, ownedAppId, "O");
        vulnBuilder("inOwned", VulnerabilitySeverity.HIGH, 40).assessment(ownedAsmt).save();
        vuln("inOther", VulnerabilitySeverity.HIGH, 40); // default app, not owned

        var ownerAuth = auth("owner", Permission.VULNERABILITIES_READ_OWNED.getPermission());
        // Asking for an owned and an unowned application keeps the owned one and never widens.
        assertThat(names(service.list(null, null, null, List.of(ownedAppId, appId), null, null, null, null,
                false, PAGE, ownerAuth).getContent())).containsExactly("inOwned");
    }

    @Test
    void filtersByVulnerabilityStatus_onBothRowTypes() {
        vulnBuilder("OpenVuln", VulnerabilitySeverity.HIGH, 40).status("Open").save();
        vulnBuilder("PastDueVuln", VulnerabilitySeverity.HIGH, 40).status("Past Due").save();

        // A retest row matches on the status of the vuln being retested, not on its own retest status.
        var retested = vulnBuilder("RetestedVuln", VulnerabilitySeverity.HIGH, 0).status("In Retest").save();
        retestRepository.save(baseRetest(retested, "SCHEDULED", assessmentId, appId)
                .scheduledEndDate(LocalDateTime.now().plusDays(5)).build());

        assertThat(names(byStatus("Open"))).containsExactly("OpenVuln");
        assertThat(names(byStatus("In Retest"))).containsExactly("RetestedVuln");
        assertThat(names(byStatus("Open", "Past Due"))).containsExactlyInAnyOrder("OpenVuln", "PastDueVuln");
        assertThat(names(byStatus("SCHEDULED"))).isEmpty(); // the retest's own status is not matched
        assertThat(names(byStatus("Failed Retest"))).isEmpty();
    }

    @Test
    void filtersByRowType() {
        vuln("AVuln", VulnerabilitySeverity.HIGH, 40);
        retest("ARetest", "SCHEDULED", 5, 10);

        assertThat(names(byType("VULNERABILITY"))).containsExactly("AVuln");
        assertThat(names(byType("RETEST"))).containsExactly("ARetest");
        assertThat(names(byType("retest"))).containsExactly("ARetest"); // case-insensitive
        // Absent / unrecognized values are ignored rather than matching nothing.
        assertThat(names(byType(null))).containsExactlyInAnyOrder("AVuln", "ARetest");
        assertThat(names(byType(""))).containsExactlyInAnyOrder("AVuln", "ARetest");
        assertThat(names(byType("NOT_A_TYPE"))).containsExactlyInAnyOrder("AVuln", "ARetest");
    }

    @Test
    void statusFilter_matchesANullStatusAsNone() {
        var noStatus = Vulnerability.builder().name("NoStatus").severity(VulnerabilitySeverity.HIGH)
                .assessmentId(assessmentId).order(0).openedAt(LocalDateTime.now().minusDays(40))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        slaService.refresh(noStatus);
        vulnerabilityRepository.save(noStatus);
        vulnBuilder("OpenVuln", VulnerabilitySeverity.HIGH, 40).status("Open").save();

        assertThat(names(byStatus("None"))).containsExactly("NoStatus");
        assertThat(names(byStatus("None", "Open"))).containsExactlyInAnyOrder("NoStatus", "OpenVuln");
    }

    @Test
    void filtersCombineWithSearch() {
        vuln("SQLInjection", VulnerabilitySeverity.HIGH, 40);
        vuln("SQLInjectionCritical", VulnerabilitySeverity.CRITICAL, 40);

        var result = service.list("sqlinjection", List.of("HIGH"), List.of(orgId), List.of(appId), List.of(assessmentId), null, null, null, false, PAGE, superAdmin());
        assertThat(names(result.getContent())).containsExactly("SQLInjection");
    }

    // ── Scope ─────────────────────────────────────────────────────────────────────

    @Test
    void multiValueFilters_matchAnyOfTheirValues_andCombineAcrossFilters() {
        vuln("acmeHigh", VulnerabilitySeverity.HIGH, 40);            // HIGH due at 30 → past due
        vuln("acmeCritical", VulnerabilitySeverity.CRITICAL, 10);    // CRITICAL due at 7 → past due
        vuln("acmeMedium", VulnerabilitySeverity.MEDIUM, 100);       // MEDIUM due at 90 → past due
        var orgB = organization("Globex").getId();
        var appB = application(orgB, "Ledger").getId();
        vulnBuilder("globexHigh", VulnerabilitySeverity.HIGH, 40).assessment(assessment(orgB, appB, "B")).save();
        var orgC = organization("Initech").getId();
        var appC = application(orgC, "TPS").getId();
        var asmtC = assessment(orgC, appC, "C");
        vulnBuilder("initechHigh", VulnerabilitySeverity.HIGH, 40).assessment(asmtC).save();

        assertThat(names(query(null, List.of(orgId, orgB), null, null)))
                .containsExactlyInAnyOrder("acmeHigh", "acmeCritical", "acmeMedium", "globexHigh");
        assertThat(names(query(null, null, List.of(appB, appC), null)))
                .containsExactlyInAnyOrder("globexHigh", "initechHigh");
        assertThat(names(query(null, null, null, List.of(assessmentId, asmtC))))
                .containsExactlyInAnyOrder("acmeHigh", "acmeCritical", "acmeMedium", "initechHigh");
        assertThat(names(query(List.of("CRITICAL", "MEDIUM"), null, null, null)))
                .containsExactlyInAnyOrder("acmeCritical", "acmeMedium");
        // Values within a filter are ORed; separate filters still AND.
        assertThat(names(query(null, List.of(orgB, orgC), List.of(appB), null))).containsExactly("globexHigh");
        // Blank and unknown values are dropped rather than matching nothing.
        assertThat(names(query(List.of("HIGH", "NOT_A_SEVERITY", " "), null, null, null)))
                .containsExactlyInAnyOrder("acmeHigh", "globexHigh", "initechHigh");
    }

    /** Super-admin list with multi-value (severities, organizationIds, applicationIds, assessmentIds). */
    private List<RemediationRowDto> query(List<String> severities, List<String> orgIds, List<String> appIds,
                                          List<String> assessmentIds) {
        return service.list(null, severities, orgIds, appIds, assessmentIds, null, null, null, false, PAGE, superAdmin())
                .getContent();
    }

    @Test
    void orgScopedUser_seesOnlyTheirOrg() {
        vuln("inAcme", VulnerabilitySeverity.HIGH, 40); // Acme (default)
        var orgB = organization("Globex").getId();
        var appB = application(orgB, "Ledger").getId();
        var asmtB = assessment(orgB, appB, "B");
        vulnBuilder("inGlobex", VulnerabilitySeverity.HIGH, 40).assessment(asmtB).save();

        user("acme-user", orgId);
        var result = list(null, auth("acme-user", Permission.VULNERABILITIES_READ_ORG.getPermission()));
        assertThat(names(result)).containsExactly("inAcme");
    }

    @Test
    void ownedScopedUser_seesOnlyOwnedApps() {
        var owner = user("owner", orgId);
        var ownedAppId = ownedApp(orgId, "Owned", owner.getId()).getId();
        var ownedAsmt = assessment(orgId, ownedAppId, "O");
        vulnBuilder("inOwned", VulnerabilitySeverity.HIGH, 40).assessment(ownedAsmt).save();
        vuln("inOther", VulnerabilitySeverity.HIGH, 40); // default app, not owned

        var result = list(null, auth("owner", Permission.VULNERABILITIES_READ_OWNED.getPermission()));
        assertThat(names(result)).containsExactly("inOwned");
    }

    @Test
    void teamScopedUser_seesOnlyTheirTeamsRows() {
        teamUser("tester", orgId, "team-a");
        var ours = teamAssessment(orgId, appId, "Ours", "team-a");
        var theirs = teamAssessment(orgId, appId, "Theirs", "team-b");
        vulnBuilder("inTeam", VulnerabilitySeverity.HIGH, 40).assessment(ours).save();
        vulnBuilder("otherTeam", VulnerabilitySeverity.HIGH, 40).assessment(theirs).save();
        vuln("noTeam", VulnerabilitySeverity.HIGH, 40); // default assessment has no team

        var result = list(null, auth("tester", Permission.VULNERABILITIES_READ_TEAM.getPermission()));
        assertThat(names(result)).containsExactly("inTeam");
    }

    @Test
    void teamScopedUser_seesTheirTeamsRetestRows() {
        teamUser("tester", orgId, "team-a");
        var ours = teamAssessment(orgId, appId, "Ours", "team-a");
        var theirs = teamAssessment(orgId, appId, "Theirs", "team-b");
        var ourVuln = vulnBuilder("ourRetest", VulnerabilitySeverity.HIGH, 0).assessment(ours).save();
        var theirVuln = vulnBuilder("theirRetest", VulnerabilitySeverity.HIGH, 0).assessment(theirs).save();
        retestRepository.save(baseRetest(ourVuln, "SCHEDULED", ours, appId)
                .scheduledEndDate(LocalDateTime.now().plusDays(3)).build());
        retestRepository.save(baseRetest(theirVuln, "SCHEDULED", theirs, appId)
                .scheduledEndDate(LocalDateTime.now().plusDays(3)).build());

        var result = list(null, auth("tester", Permission.VULNERABILITIES_READ_TEAM.getPermission()));
        assertThat(names(result)).containsExactly("ourRetest");
    }

    @Test
    void teamScopedUser_inNoTeam_seesNothing() {
        teamUser("loner", orgId);
        vulnBuilder("inTeam", VulnerabilitySeverity.HIGH, 40)
                .assessment(teamAssessment(orgId, appId, "Ours", "team-a")).save();

        assertThat(list(null, auth("loner", Permission.VULNERABILITIES_READ_TEAM.getPermission()))).isEmpty();
    }

    @Test
    void assessmentScopedUser_seesOnlyTheirOwnAssessments() {
        var me = teamUser("tester", orgId);
        var mine = assessmentRepository.save(com.faction.clientportal.model.Assessment.builder()
                .name("Mine").applicationId(appId).assessmentTypeId("t").organizationId(orgId)
                .status("Testing").assessorIds(List.of(me.getId()))
                .createdAt(LocalDateTime.now()).build()).getId();
        vulnBuilder("mine", VulnerabilitySeverity.HIGH, 40).assessment(mine).save();
        vuln("theirs", VulnerabilitySeverity.HIGH, 40); // default assessment, no assessors

        var result = list(null, auth("tester",
                Permission.VULNERABILITIES_READ_ASSESSMENT.getPermission(),
                Permission.ASSESSMENTS_READ_ASSIGNED.getPermission()));
        assertThat(names(result)).containsExactly("mine");
    }

    @Test
    void orgScopedUser_withNoResolvableOrg_seesNothing() {
        vuln("v", VulnerabilitySeverity.HIGH, 40);
        var result = list(null, auth("ghost", Permission.VULNERABILITIES_READ_ORG.getPermission()));
        assertThat(result).isEmpty();
    }

    // ── Stored dates, not the config at read time ────────────────────────────────

    @Test
    void listsFromTheStoredDates_notTheConfigAtReadTime() {
        // Stored under setUp's SLAs; both not yet due, so the live past-due job leaves them alone.
        vuln("StoredWarning", VulnerabilitySeverity.CRITICAL, 5); // due +2d, warning −1d → queued
        vuln("StoredFresh", VulnerabilitySeverity.HIGH, 10);      // due +20d, warning +5d → not queued

        // No recalculation: computed from this config the queue would be exactly "StoredFresh".
        configureSlas(
                new VulnerabilitySla("CRITICAL", 30, 15),
                new VulnerabilitySla("HIGH", 12, 5));

        var rows = list();
        assertThat(names(rows)).containsExactly("StoredWarning");
        assertThat(row(rows, "StoredWarning").isWarning()).isTrue();
        assertThat(row(rows, "StoredWarning").isUrgent()).isFalse();
    }

    // ── Pagination ──────────────────────────────────────────────────────────────

    @Test
    void paginates_withStableTotalAndNoDuplicates() {
        for (int i = 0; i < 6; i++) vuln("same", VulnerabilitySeverity.HIGH, 40); // tied tier + due date

        var seen = new java.util.HashSet<String>();
        long total = -1;
        for (int p = 0; p < 3; p++) {
            var page = service.list(null, null, null, null, null, null, null, null, false, PageRequest.of(p, 2), superAdmin());
            total = page.getTotalElements();
            page.getContent().forEach(d -> assertThat(seen.add(d.getKey())).isTrue());
        }
        assertThat(total).isEqualTo(6);
        assertThat(seen).hasSize(6);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private List<String> names(List<RemediationRowDto> l) {
        return l.stream().map(RemediationRowDto::getVulnerabilityName).toList();
    }

    private RemediationRowDto row(List<RemediationRowDto> l, String name) {
        return l.stream().filter(d -> name.equals(d.getVulnerabilityName())).findFirst().orElseThrow();
    }

    // ── Summary badges and the nav count ─────────────────────────────────────────

    private com.faction.clientportal.dto.RemediationQueueSummaryDto summary(Authentication auth) {
        return service.summary(null, null, null, null, null, null, null, auth);
    }

    /** One row in every badge bucket (two in two of them), plus a verified retest that is none. */
    private void seedEveryBucket() {
        vuln("pastDueHigh", VulnerabilitySeverity.HIGH, 40);          // HIGH due at 30 → past due
        vuln("pastDueCritical", VulnerabilitySeverity.CRITICAL, 10);  // CRITICAL due at 7 → past due
        vuln("dueSoonHigh", VulnerabilitySeverity.HIGH, 20);          // HIGH warning from 15 → due soon
        retest("requested", "REQUESTED", null, null);
        retest("scheduled", "SCHEDULED", 1, 5);
        retest("inProgressA", "IN_PROGRESS", -2, 3);
        retest("inProgressB", "IN_PROGRESS", -2, 3);
        retest("passed", "PASSED", -20, -15);                         // verified: not queue work
    }

    @Test
    void summary_breaksTheQueueIntoBuckets_thatAddUpToTheTotal() {
        seedEveryBucket();

        var s = summary(superAdmin());

        assertThat(s.pastDue()).isEqualTo(2);
        assertThat(s.dueSoon()).isEqualTo(1);
        assertThat(s.retestRequested()).isEqualTo(1);
        assertThat(s.retestScheduled()).isEqualTo(1);
        assertThat(s.retestInProgress()).isEqualTo(2);
        // The badges partition the queue: they add up to the rows the table shows.
        assertThat(s.total()).isEqualTo(7).isEqualTo(list().size());
    }

    @Test
    void summary_followsTheOtherFilters() {
        seedEveryBucket();

        var vulnsOnly = service.summary(null, null, null, null, null, null, "VULNERABILITY", superAdmin());
        assertThat(vulnsOnly.pastDue()).isEqualTo(2);
        assertThat(vulnsOnly.dueSoon()).isEqualTo(1);
        assertThat(vulnsOnly.retestRequested() + vulnsOnly.retestScheduled() + vulnsOnly.retestInProgress()).isZero();
        assertThat(vulnsOnly.total()).isEqualTo(3);

        var critical = service.summary(null, List.of("CRITICAL"), null, null, null, null, null, superAdmin());
        assertThat(critical.pastDue()).isEqualTo(1);
        assertThat(critical.total()).isEqualTo(1);
    }

    @Test
    void summary_countsSeveralSeverities() {
        seedEveryBucket();

        var s = service.summary(null, List.of("CRITICAL", "HIGH"), null, null, null, null, "VULNERABILITY", superAdmin());
        assertThat(s.pastDue()).isEqualTo(2);
        assertThat(s.dueSoon()).isEqualTo(1);
        assertThat(s.total()).isEqualTo(3);
    }

    @Test
    void bucketFilter_narrowsTheListToTheChosenBadges() {
        seedEveryBucket();

        var rows = service.list(null, null, null, null, null, null, null,
                List.of("PAST_DUE", "RETEST_SCHEDULED"), false, PAGE, superAdmin()).getContent();

        assertThat(names(rows)).containsExactlyInAnyOrder("pastDueHigh", "pastDueCritical", "scheduled");
    }

    @Test
    void summaryAndNavCount_areScopedToWhatTheCallerMayRead() {
        vuln("inAcme", VulnerabilitySeverity.HIGH, 40); // Acme (default)
        var orgB = organization("Globex").getId();
        var appB = application(orgB, "Ledger").getId();
        var asmtB = assessment(orgB, appB, "B");
        vulnBuilder("inGlobex", VulnerabilitySeverity.HIGH, 40).assessment(asmtB).save();
        user("acme-user", orgId);
        var acme = auth("acme-user", Permission.VULNERABILITIES_READ_ORG.getPermission());

        assertThat(summary(acme).pastDue()).isEqualTo(1);
        assertThat(service.queueCount(acme)).isEqualTo(1);
        assertThat(summary(superAdmin()).pastDue()).isEqualTo(2);
        assertThat(service.queueCount(superAdmin())).isEqualTo(2);
    }

    private void configureSlas(VulnerabilitySla... slas) {
        workflowConfigRepository.save(AssessmentWorkflow.defaultWorkflowBuilder()
                .vulnerabilitySlas(List.of(slas))
                .build());
    }

    private Organization organization(String name) {
        return organizationRepository.save(Organization.builder().name(name).description("d").build());
    }

    private Application application(String orgId, String name) {
        return applicationRepository.save(Application.builder().name(name).organizationId(orgId).build());
    }

    private Application ownedApp(String orgId, String name, String userId) {
        return applicationRepository.save(Application.builder().name(name).organizationId(orgId)
                .assignedUsers(List.of(AssignedUser.builder().userId(userId).accessLevel("WRITE").build())).build());
    }

    private String assessment(String orgId, String appId, String name) {
        return teamAssessment(orgId, appId, name, null);
    }

    private String teamAssessment(String orgId, String appId, String name, String teamId) {
        return assessmentRepository.save(com.faction.clientportal.model.Assessment.builder()
                .name(name).applicationId(appId).assessmentTypeId("t").organizationId(orgId)
                .status("Testing").teamId(teamId).createdAt(LocalDateTime.now()).build()).getId();
    }

    private User teamUser(String username, String orgId, String... teamIds) {
        return userRepository.save(User.builder()
                .username(username).firstName("T").lastName("U").email(username + "@test.com")
                .password("x").loginOption(LoginOption.NATIVE).organizationId(orgId)
                .teamIds(List.of(teamIds))
                .isInternal(true).failedLoginAttempts(0).createdAt(LocalDateTime.now()).build());
    }

    private String deletedAssessment(String orgId, String appId) {
        return assessmentRepository.save(com.faction.clientportal.model.Assessment.builder()
                .name("Gone").applicationId(appId).assessmentTypeId("t").organizationId(orgId)
                .status("Testing").deletedAt(LocalDateTime.now()).createdAt(LocalDateTime.now()).build()).getId();
    }

    /** Seed a queue vuln on the default assessment, opened {@code openedDaysAgo} days ago. */
    private String vuln(String name, VulnerabilitySeverity sev, int openedDaysAgo) {
        return vulnBuilder(name, sev, openedDaysAgo).save();
    }

    /** A fresh (opened today) vuln that never enters the queue itself — just a retest target. */
    private String freshVuln(String name) {
        return vulnBuilder(name, VulnerabilitySeverity.HIGH, 0).save();
    }

    private VulnBuilder vulnBuilder(String name, VulnerabilitySeverity sev, int openedDaysAgo) {
        return new VulnBuilder(name, sev, openedDaysAgo);
    }

    /** Small fluent wrapper so exclusion cases read as one-liners. */
    private final class VulnBuilder {
        private final Vulnerability.VulnerabilityBuilder b;
        private VulnBuilder(String name, VulnerabilitySeverity sev, int openedDaysAgo) {
            b = Vulnerability.builder().name(name).severity(sev).assessmentId(assessmentId).order(0)
                    .status("Open").openedAt(LocalDateTime.now().minusDays(openedDaysAgo))
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now());
        }
        VulnBuilder status(String s) { b.status(s); return this; }
        VulnBuilder openedAtNull() { b.openedAt(null); return this; }
        VulnBuilder softDeleted() { b.deletedAt(LocalDateTime.now()); return this; }
        VulnBuilder assessment(String id) { b.assessmentId(id); return this; }
        String save() {
            Vulnerability v = b.build();
            // Stored dates from the config in force now, as every real write sets them.
            slaService.refresh(v);
            return vulnerabilityRepository.save(v).getId();
        }
    }

    /** Seed an open retest (with a fresh underlying vuln named {@code name}) on the default assessment. */
    private void retest(String name, String status, Integer startInDays, Integer endInDays) {
        var v = freshVuln(name);
        var rb = baseRetest(v, status, assessmentId, appId);
        if (startInDays != null) rb.scheduledStartDate(LocalDateTime.now().plusDays(startInDays));
        if (endInDays != null) rb.scheduledEndDate(LocalDateTime.now().plusDays(endInDays));
        retestRepository.save(rb.build());
    }

    private Retest.RetestBuilder baseRetest(String vulnId, String status, String asmtId, String applicationId) {
        return Retest.builder()
                .vulnerabilityId(vulnId).assessmentId(asmtId).applicationId(applicationId)
                .status(status).createdBy("system").lastUpdatedBy("system")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now());
    }

    private User user(String username, String orgId) {
        return userRepository.save(User.builder()
                .username(username).firstName("T").lastName("U").email(username + "@test.com")
                .password("x").loginOption(LoginOption.NATIVE).organizationId(orgId)
                .isInternal(false).failedLoginAttempts(0).createdAt(LocalDateTime.now()).build());
    }

    private Authentication superAdmin() {
        return auth("super", RequiresPermissionAuthorizationManager.SUPER_ADMIN);
    }

    private Authentication auth(String username, String... authorities) {
        List<GrantedAuthority> granted = Arrays.stream(authorities)
                .map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a)).toList();
        return new UsernamePasswordAuthenticationToken(username, null, granted);
    }
}
