package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.RemediationRowDto;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.AssessmentWorkflowConfig;
import com.faction.clientportal.model.AssessmentWorkflowConfig.VulnerabilitySla;
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
import com.faction.clientportal.repository.AssessmentWorkflowConfigRepository;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.RetestRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.security.RequiresPermissionAuthorizationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
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
    @Autowired private AssessmentWorkflowConfigRepository workflowConfigRepository;

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
        return service.list(search, null, null, null, null, null, null, false, PAGE, auth).getContent();
    }

    /** Filtered list as a super admin: (severity, organizationId, applicationId, assessmentId). */
    private List<RemediationRowDto> filtered(String severity, String orgFilter, String appFilter, String asmtFilter) {
        return service.list(null, severity, orgFilter, appFilter, asmtFilter, null, null, false, PAGE, superAdmin()).getContent();
    }

    /** Filtered list as a super admin, by row type ("VULNERABILITY" / "RETEST"). */
    private List<RemediationRowDto> byType(String type) {
        return service.list(null, null, null, null, null, null, type, false, PAGE, superAdmin()).getContent();
    }

    /** Filtered list as a super admin, by vulnerability status. */
    private List<RemediationRowDto> byStatus(String... statuses) {
        return service.list(null, null, null, null, null, List.of(statuses), null, false, PAGE, superAdmin()).getContent();
    }

    // ── Completed retests (opt-in) ───────────────────────────────────────────────

    /** Queue as a super admin with verified retests included. */
    private List<RemediationRowDto> withCompletedRetests() {
        return service.list(null, null, null, null, null, null, null, true, PAGE, superAdmin()).getContent();
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

        var page = service.list(null, null, null, null, null, null, null, true,
                PageRequest.of(0, 1), superAdmin());
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(1);
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
        return service.exportCsv(null, null, null, null, null, null, null,
                includeCompletedRetests, Sort.unsorted(), superAdmin());
    }

    @Test
    void exportCsv_writesHeaderAndOneRowPerQueueItem() {
        vuln("SQL Injection", VulnerabilitySeverity.HIGH, 40);

        var lines = exportCsv(false).split("\n");

        assertThat(lines[0]).isEqualTo("Type,Vulnerability,Severity,Status,Application,Organization,"
                + "Due Date,Scheduled Start,Scheduled End,Retest Status,Last Retest,"
                + "Completed Date,Result,Completed By");
        assertThat(lines).hasSize(2);
        assertThat(lines[1]).contains("VULNERABILITY", "SQL Injection", "HIGH", "Payments API", "Acme");
    }

    @Test
    void exportCsv_carriesRetestCompletionColumns() {
        // The point of the export: when a retest closed, how it went, and who signed off — none of
        // which the queue's union query (or the table) carries.
        var v = freshVuln("Retested");
        LocalDateTime closed = LocalDateTime.now().minusDays(3);
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

        var csv = service.exportCsv(null, null, null, null, null, null, null, false, Sort.unsorted(),
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
        assertThat(service.list(null, null, orgB, null, null, null, null, false, PAGE, acmeAuth).getContent()).isEmpty();
        // Asking for their own org still works.
        assertThat(names(service.list(null, null, orgId, null, null, null, null, false, PAGE, acmeAuth).getContent()))
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
        assertThat(service.list(null, null, null, appId, null, null, null, false, PAGE, ownerAuth).getContent()).isEmpty();
        assertThat(names(service.list(null, null, null, ownedAppId, null, null, null, false, PAGE, ownerAuth).getContent()))
                .containsExactly("inOwned");
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
        vulnerabilityRepository.save(noStatus);
        vulnBuilder("OpenVuln", VulnerabilitySeverity.HIGH, 40).status("Open").save();

        assertThat(names(byStatus("None"))).containsExactly("NoStatus");
        assertThat(names(byStatus("None", "Open"))).containsExactlyInAnyOrder("NoStatus", "OpenVuln");
    }

    @Test
    void filtersCombineWithSearch() {
        vuln("SQLInjection", VulnerabilitySeverity.HIGH, 40);
        vuln("SQLInjectionCritical", VulnerabilitySeverity.CRITICAL, 40);

        var result = service.list("sqlinjection", "HIGH", orgId, appId, assessmentId, null, null, false, PAGE, superAdmin());
        assertThat(names(result.getContent())).containsExactly("SQLInjection");
    }

    // ── Scope ─────────────────────────────────────────────────────────────────────

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
                .status("IN_PROGRESS").assessorIds(List.of(me.getId()))
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

    // ── Pagination ──────────────────────────────────────────────────────────────

    @Test
    void paginates_withStableTotalAndNoDuplicates() {
        for (int i = 0; i < 6; i++) vuln("same", VulnerabilitySeverity.HIGH, 40); // tied tier + due date

        var seen = new java.util.HashSet<String>();
        long total = -1;
        for (int p = 0; p < 3; p++) {
            var page = service.list(null, null, null, null, null, null, null, false, PageRequest.of(p, 2), superAdmin());
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

    private void configureSlas(VulnerabilitySla... slas) {
        workflowConfigRepository.save(AssessmentWorkflowConfig.builder()
                .id("singleton")
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
                .status("IN_PROGRESS").teamId(teamId).createdAt(LocalDateTime.now()).build()).getId();
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
                .status("IN_PROGRESS").deletedAt(LocalDateTime.now()).createdAt(LocalDateTime.now()).build()).getId();
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
        String save() { return vulnerabilityRepository.save(b.build()).getId(); }
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
