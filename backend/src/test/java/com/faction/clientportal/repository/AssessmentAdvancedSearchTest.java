package com.faction.clientportal.repository;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity coverage for the DB-side advanced assessment search ({@link AssessmentRepositoryCustom}).
 * Each test pins one filter's SQL semantics against the in-memory behavior it replaced — the
 * parity-sensitive ones being: null dates pass range filters, excludeCompleted keeps null-status
 * rows, empty owned/team scopes match nothing, and the JSONB assessor/team/severity predicates.
 */
@SpringBootTest
@ActiveProfiles("test")
class AssessmentAdvancedSearchTest extends TestContainersConfig {

    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private AssessmentTypeRepository assessmentTypeRepository;

    private static final CompletedStatusFilter DEFAULT_ONLY = new CompletedStatusFilter(
            List.of("default"), List.of("Completed"), List.of("default"), "default");
    private static final CompletedStatusFilter TWO_WORKFLOWS = new CompletedStatusFilter(
            List.of("default", "second-workflow"), List.of("Completed", "Signed Off"),
            List.of("default", "second-workflow"), "default");
    private static final Pageable PAGE = PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "name"));

    @BeforeEach
    void setUp() {
        vulnerabilityRepository.deleteAll();
        assessmentRepository.deleteAll();
        applicationRepository.deleteAll();
        assessmentTypeRepository.deleteAll();
    }

    private AssessmentSearchCriteria.AssessmentSearchCriteriaBuilder base() {
        return AssessmentSearchCriteria.builder()
                .completed(DEFAULT_ONLY)
                .now(LocalDateTime.now());
    }

    private List<Assessment> search(AssessmentSearchCriteria c) {
        return assessmentRepository.searchAdvanced(c, PAGE).getContent();
    }

    // ── Baseline / deleted / pagination ────────────────────────────────────────

    @Test
    void excludesSoftDeleted_andCountsTotal() {
        save(a("Live").status("Testing"));
        save(a("Gone").status("Testing").deletedAt(LocalDateTime.now()));

        var page = assessmentRepository.searchAdvanced(base().build(), PAGE);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(Assessment::getName).containsExactly("Live");
    }

    @Test
    void paginates_withCorrectTotal() {
        for (int i = 0; i < 5; i++) save(a("A" + i).status("Testing"));

        var page = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(1, 2, Sort.by(Sort.Direction.ASC, "name")));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent()).extracting(Assessment::getName).containsExactly("A2", "A3");
    }

    // ── Search (name substring, case-insensitive, literal wildcards) ────────────

    @Test
    void search_isCaseInsensitiveSubstring() {
        save(a("Web App Pentest").status("Testing"));
        save(a("Mobile Review").status("Testing"));

        assertThat(search(base().search("web").build())).extracting(Assessment::getName).containsExactly("Web App Pentest");
        assertThat(search(base().search("PENTEST").build())).extracting(Assessment::getName).containsExactly("Web App Pentest");
    }

    /**
     * The search box on the Scheduling and Assessments pages is where someone types the name of
     * the application they are looking for at least as often as the assessment's own name, so the
     * term matches either.
     */
    @Test
    void search_matchesApplicationNameAsWellAsAssessmentName() {
        String banking = application("APP-1", "Commercial Banking Portal");
        String other = application("APP-2", "Payroll");
        save(a("Q3 Pentest").applicationId(banking).status("Testing"));
        save(a("Q3 Pentest").applicationId(other).status("Testing"));
        save(a("Banking Mobile Review").applicationId(other).status("Testing"));

        var result = search(base().search("banking").build());

        assertThat(result).extracting(Assessment::getApplicationId, Assessment::getName)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(banking, "Q3 Pentest"),
                        org.assertj.core.groups.Tuple.tuple(other, "Banking Mobile Review"));
    }

    @Test
    void search_treatsWildcardsLiterally() {
        save(a("app_01").status("Testing"));
        save(a("appX01").status("Testing"));

        // '_' must match literally, not as a single-char wildcard.
        assertThat(search(base().search("app_0").build())).extracting(Assessment::getName).containsExactly("app_01");
    }

    // ── Membership (ORG) scope: organizations OR sub-organization-granted applications ──

    @Test
    void orgScope_matchesOrganizationOrScopedApplication() {
        String appX = application("APP-X", "X");
        String appY = application("APP-Y", "Y");
        save(a("In org").organizationId("org-1").applicationId(appY).status("Testing"));
        save(a("Scoped app").organizationId("org-2").applicationId(appX).status("Testing"));
        save(a("Neither").organizationId("org-2").applicationId(appY).status("Testing"));

        var result = search(base().scopeOrgIds(Set.of("org-1")).scopeAppIds(Set.of(appX)).build());
        assertThat(result).extracting(Assessment::getName).containsExactlyInAnyOrder("In org", "Scoped app");

        assertThat(search(base().scopeOrgIds(Set.of("org-1")).scopeAppIds(Set.of()).build()))
                .extracting(Assessment::getName).containsExactly("In org");
        assertThat(search(base().scopeOrgIds(Set.of()).scopeAppIds(Set.of(appX)).build()))
                .extracting(Assessment::getName).containsExactly("Scoped app");
        // A membership scope that grants nothing matches nothing — never falls through to "all".
        assertThat(search(base().scopeOrgIds(Set.of()).scopeAppIds(Set.of()).build())).isEmpty();
    }

    @Test
    void assessmentTypeIds_matchesAny_emptyMeansNoFilter() {
        save(a("Web").assessmentTypeId("type-web").status("Testing"));
        save(a("Mobile").assessmentTypeId("type-mobile").status("Testing"));
        save(a("Cloud").assessmentTypeId("type-cloud").status("Testing"));

        assertThat(search(base().assessmentTypeIds(List.of("type-web", "type-cloud")).build()))
                .extracting(Assessment::getName).containsExactlyInAnyOrder("Web", "Cloud");
        assertThat(search(base().assessmentTypeIds(List.of()).build())).hasSize(3);
    }

    // ── Equality filters ────────────────────────────────────────────────────────

    @Test
    void filtersByApplicationOrgTypeCampaign() {
        save(a("Match").applicationId("app-1").organizationId("org-1").assessmentTypeId("t-1").campaignId("c-1").status("Testing"));
        save(a("Other").applicationId("app-2").organizationId("org-2").assessmentTypeId("t-2").campaignId("c-2").status("Testing"));

        assertThat(search(base().applicationId("app-1").build())).extracting(Assessment::getName).containsExactly("Match");
        assertThat(search(base().organizationId("org-1").build())).extracting(Assessment::getName).containsExactly("Match");
        assertThat(search(base().assessmentTypeId("t-1").build())).extracting(Assessment::getName).containsExactly("Match");
        assertThat(search(base().campaignId("c-1").build())).extracting(Assessment::getName).containsExactly("Match");
    }

    @Test
    void status_isCaseInsensitive() {
        save(a("A").status("data gathering"));
        assertThat(search(base().status("Data Gathering").build())).extracting(Assessment::getName).containsExactly("A");
    }

    // ── Owned scope ────────────────────────────────────────────────────────────

    @Test
    void ownedScope_restrictsToApps_andEmptyMatchesNothing() {
        save(a("Owned").applicationId("app-1").status("Testing"));
        save(a("NotOwned").applicationId("app-9").status("Testing"));

        assertThat(search(base().ownedAppIds(List.of("app-1")).build())).extracting(Assessment::getName).containsExactly("Owned");
        assertThat(search(base().ownedAppIds(List.of()).build())).isEmpty(); // empty owned → nothing
    }

    @Test
    void applicationIds_restrictsToSelectedApps_emptyMeansNoFilter() {
        save(a("A1").applicationId("app-1").status("Testing"));
        save(a("A2").applicationId("app-2").status("Testing"));
        save(a("A3").applicationId("app-3").status("Testing"));

        assertThat(search(base().applicationIds(List.of("app-1", "app-3")).build()))
                .extracting(Assessment::getName).containsExactlyInAnyOrder("A1", "A3");
        // Empty multi-app filter is "no filter" (unlike the owned scope, which matches nothing).
        assertThat(search(base().applicationIds(List.of()).build())).hasSize(3);
    }

    // ── Date ranges (an active date filter excludes out-of-range AND undated rows) ──

    @Test
    void startDateRange_excludesOutOfRangeAndUndated() {
        var now = LocalDateTime.now();
        save(a("InRange").status("Testing").startDate(now.minusDays(1)));
        save(a("TooEarly").status("Testing").startDate(now.minusDays(30)));
        save(a("NullStart").status("Testing").startDate(null));

        var result = search(base().startDateFrom(now.minusDays(5)).startDateTo(now).build());

        assertThat(result).extracting(Assessment::getName).containsExactly("InRange");
    }

    @Test
    void completedDateRange_excludesOutOfRangeAndUndated() {
        var now = LocalDateTime.now();
        save(a("InRange").status("Completed").completedDate(now.minusDays(1)));
        save(a("TooEarly").status("Completed").completedDate(now.minusDays(30)));
        save(a("NeverCompleted").status("Testing").completedDate(null));

        var result = search(base().completedDateFrom(now.minusDays(5)).completedDateTo(now).build());

        assertThat(result).extracting(Assessment::getName).containsExactly("InRange");
    }

    // ── Completed / past due ────────────────────────────────────────────────────

    @Test
    void excludeCompleted_dropsCompletedStatuses_butKeepsNullStatus() {
        save(a("Active").status("Testing"));
        save(a("Done").status("Completed"));
        // Just completed: still inside the reopen window, and still hidden — "show completed" is
        // the only way a completed assessment reaches the list.
        save(a("JustDone").status("Completed").completedDate(LocalDateTime.now().minusDays(1)));
        save(a("NoStatus").status(null));

        var result = search(base().excludeCompleted(true).build());

        assertThat(result).extracting(Assessment::getName).containsExactlyInAnyOrder("Active", "NoStatus");
    }

    @Test
    void pastDue_onlyOverdueNonCompleted() {
        var now = LocalDateTime.now();
        save(a("Overdue").status("Testing").plannedEndDate(now.minusDays(1)));
        save(a("Future").status("Testing").plannedEndDate(now.plusDays(1)));
        save(a("OverdueButDone").status("Completed").plannedEndDate(now.minusDays(1)));

        var result = search(base().pastDue(true).build());

        assertThat(result).extracting(Assessment::getName).containsExactly("Overdue");
    }

    @Test
    void excludeCompleted_usesEachAssessmentsOwnWorkflowsCompletedStatus() {
        save(a("DefaultDone").status("Completed"));
        save(a("DefaultSignedOff").status("Signed Off"));
        save(a("SecondDone").workflowId("second-workflow").status("Signed Off"));
        save(a("SecondCompleted").workflowId("second-workflow").status("Completed"));
        save(a("UnknownDone").workflowId("gone-workflow").status("Completed"));
        save(a("UnknownSignedOff").workflowId("gone-workflow").status("Signed Off"));
        save(a("SecondNoStatus").workflowId("second-workflow").status(null));

        var result = search(base().completed(TWO_WORKFLOWS).excludeCompleted(true).build());

        assertThat(result).extracting(Assessment::getName).containsExactlyInAnyOrder(
                "DefaultSignedOff", "SecondCompleted", "UnknownSignedOff", "SecondNoStatus");
    }

    @Test
    void excludeCompleted_aKnownWorkflowWithNoCompletedStatusCompletesNothing() {
        var filter = new CompletedStatusFilter(List.of("default"), List.of("Completed"),
                List.of("default", "no-end"), "default");
        save(a("NoEndCompleted").workflowId("no-end").status("Completed"));
        save(a("DefaultDone").status("Completed"));

        var result = search(base().completed(filter).excludeCompleted(true).build());

        assertThat(result).extracting(Assessment::getName).containsExactly("NoEndCompleted");
    }

    @Test
    void pastDue_usesEachAssessmentsOwnWorkflowsCompletedStatus() {
        var late = LocalDateTime.now().minusDays(1);
        save(a("SecondDoneLate").workflowId("second-workflow").status("Signed Off").plannedEndDate(late));
        save(a("SecondCompletedLate").workflowId("second-workflow").status("Completed").plannedEndDate(late));
        save(a("UnknownDoneLate").workflowId("gone-workflow").status("Completed").plannedEndDate(late));

        var result = search(base().completed(TWO_WORKFLOWS).pastDue(true).build());

        assertThat(result).extracting(Assessment::getName).containsExactly("SecondCompletedLate");
    }

    @Test
    void excludeCompleted_countQueryBindsTheSameFilter() {
        save(a("ActiveOne").workflowId("second-workflow").status("Fieldwork"));
        save(a("ActiveTwo").workflowId("second-workflow").status("Draft"));
        save(a("Done").workflowId("second-workflow").status("Signed Off"));

        var page = assessmentRepository.searchAdvanced(
                base().completed(TWO_WORKFLOWS).excludeCompleted(true).build(), PageRequest.of(0, 1));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    // ── Assigned-to-me (managers, legacy assessor, assessorIds JSONB) ───────────

    @Test
    void assignedToMe_matchesManagersLegacyAndAssessorIds() {
        save(a("ByEngMgr").status("Testing").engagementManagerId("me"));
        save(a("ByRemMgr").status("Testing").remediationManagerId("me"));
        save(a("ByLegacy").status("Testing").assessorId("me"));
        save(a("ByAssessorIds").status("Testing").assessorIds(List.of("x", "me")));
        save(a("NotMine").status("Testing").assessorIds(List.of("someone")));

        var result = search(base().assignedToMe(true).currentUserId("me").build());

        assertThat(result).extracting(Assessment::getName)
                .containsExactlyInAnyOrder("ByEngMgr", "ByRemMgr", "ByLegacy", "ByAssessorIds");
    }

    @Test
    void assessorId_matchesLegacyOrAssessorIds() {
        save(a("Legacy").status("Testing").assessorId("u1"));
        save(a("InArray").status("Testing").assessorIds(List.of("u1")));
        save(a("Neither").status("Testing").assessorIds(List.of("u2")));

        assertThat(search(base().assessorId("u1").build())).extracting(Assessment::getName)
                .containsExactlyInAnyOrder("Legacy", "InArray");
    }

    // ── Team membership (assessorIds ∩ members) ─────────────────────────────────

    @Test
    void teamMembers_intersectAssessorIds_andEmptyMatchesNothing() {
        save(a("HasMember").status("Testing").assessorIds(List.of("u1", "u3")));
        save(a("NoMember").status("Testing").assessorIds(List.of("u9")));

        assertThat(search(base().teamMemberIds(List.of("u1", "u2")).build())).extracting(Assessment::getName).containsExactly("HasMember");
        assertThat(search(base().teamMemberIds(List.of()).build())).isEmpty(); // team with no members → nothing
    }

    // ── Severity (EXISTS opened vuln of severity within date range) ─────────────

    @Test
    void severities_keepAssessmentsWithMatchingOpenedVuln() {
        var withHigh = save(a("HasHigh").status("Testing")).getId();
        var withLow = save(a("HasLow").status("Testing")).getId();
        vuln(withHigh, VulnerabilitySeverity.HIGH, LocalDateTime.now().minusDays(1));
        vuln(withLow, VulnerabilitySeverity.LOW, LocalDateTime.now().minusDays(1));

        var result = search(base().severityOrdinals(List.of(VulnerabilitySeverity.HIGH.ordinal())).build());

        assertThat(result).extracting(Assessment::getName).containsExactly("HasHigh");
    }

    @Test
    void severities_respectOpenedAtDateWindow() {
        // The date range constrains both the assessment's start_date and the severity sub-query's
        // opened_at window, so both assessments are started in-range; the vuln opened_at is the discriminator.
        var now = LocalDateTime.now();
        var a1 = save(a("Recent").status("Testing").startDate(now.minusDays(1))).getId();
        var a2 = save(a("Old").status("Testing").startDate(now.minusDays(1))).getId();
        vuln(a1, VulnerabilitySeverity.HIGH, now.minusDays(1));
        vuln(a2, VulnerabilitySeverity.HIGH, now.minusDays(30));

        var result = search(base()
                .severityOrdinals(List.of(VulnerabilitySeverity.HIGH.ordinal()))
                .startDateFrom(now.minusDays(5)).startDateTo(now).build());

        assertThat(result).extracting(Assessment::getName).containsExactly("Recent");
    }

    // ── Sorting ─────────────────────────────────────────────────────────────────

    @Test
    void sortsByNameDescending() {
        save(a("Alpha").status("Testing"));
        save(a("Charlie").status("Testing"));
        save(a("Bravo").status("Testing"));

        var result = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "name"))).getContent();

        assertThat(result).extracting(Assessment::getName).containsExactly("Charlie", "Bravo", "Alpha");
    }

    // ── End-date range (mirror of start-date; a date filter excludes undated) ────

    @Test
    void endDateRange_excludesOutOfRangeAndUndated() {
        var now = LocalDateTime.now();
        save(a("InRange").status("Testing").plannedEndDate(now.minusDays(1)));
        save(a("TooEarly").status("Testing").plannedEndDate(now.minusDays(30)));
        save(a("NullEnd").status("Testing").plannedEndDate(null));

        var result = search(base().endDateFrom(now.minusDays(5)).endDateTo(now).build());

        assertThat(result).extracting(Assessment::getName).containsExactly("InRange");
    }

    // ── Filter combinations (AND semantics — what the UX actually issues) ────────

    @Test
    void combinesFilters_appStatusAndDateNarrowTogether() {
        var now = LocalDateTime.now();
        save(a("Target").applicationId("app-1").status("Testing").startDate(now.minusDays(1)));
        save(a("WrongApp").applicationId("app-2").status("Testing").startDate(now.minusDays(1)));
        save(a("WrongStatus").applicationId("app-1").status("Completed").startDate(now.minusDays(1)));
        save(a("WrongDate").applicationId("app-1").status("Testing").startDate(now.minusDays(60)));

        var result = search(base().applicationId("app-1").status("Testing")
                .startDateFrom(now.minusDays(5)).startDateTo(now).build());

        assertThat(result).extracting(Assessment::getName).containsExactly("Target");
    }

    @Test
    void combinesFilters_ownedScopePlusStatus() {
        save(a("Keep").applicationId("app-1").status("Testing"));
        save(a("WrongStatus").applicationId("app-1").status("Completed"));
        save(a("Unowned").applicationId("app-9").status("Testing"));

        var result = search(base().ownedAppIds(List.of("app-1")).status("Testing").build());

        assertThat(result).extracting(Assessment::getName).containsExactly("Keep");
    }

    @Test
    void combinesFilters_searchPlusOrg() {
        save(a("Web Test").organizationId("org-1").status("Testing"));
        save(a("Web Test").organizationId("org-2").status("Testing")); // same name, other org
        save(a("Mobile Test").organizationId("org-1").status("Testing"));

        var result = search(base().search("web").organizationId("org-1").build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOrganizationId()).isEqualTo("org-1");
    }

    @Test
    void combinesFilters_severityDateWindowAndStatus() {
        var now = LocalDateTime.now();
        var keep = save(a("Keep").status("Testing").startDate(now.minusDays(1))).getId();
        var wrongStatus = save(a("WrongStatus").status("Completed").startDate(now.minusDays(1))).getId();
        var wrongWindow = save(a("WrongWindow").status("Testing").startDate(now.minusDays(1))).getId();
        vuln(keep, VulnerabilitySeverity.HIGH, now.minusDays(1));
        vuln(wrongStatus, VulnerabilitySeverity.HIGH, now.minusDays(1));
        vuln(wrongWindow, VulnerabilitySeverity.HIGH, now.minusDays(60));

        var result = search(base().severityOrdinals(List.of(VulnerabilitySeverity.HIGH.ordinal()))
                .status("Testing").startDateFrom(now.minusDays(5)).startDateTo(now).build());

        assertThat(result).extracting(Assessment::getName).containsExactly("Keep");
    }

    // ── Sorting (fields, directions, default, NULLS LAST) ───────────────────────

    @Test
    void unsorted_defaultsToCreatedAtDescending() {
        var t0 = LocalDateTime.now();
        save(a("First").status("Testing").createdAt(t0.minusDays(2)));
        save(a("Second").status("Testing").createdAt(t0.minusDays(1)));
        save(a("Third").status("Testing").createdAt(t0));

        var result = assessmentRepository.searchAdvanced(base().build(), PageRequest.of(0, 50)).getContent();

        assertThat(result).extracting(Assessment::getName).containsExactly("Third", "Second", "First");
    }

    @Test
    void sortsByCreatedAtAscending() {
        var t0 = LocalDateTime.now();
        save(a("First").status("Testing").createdAt(t0.minusDays(2)));
        save(a("Second").status("Testing").createdAt(t0.minusDays(1)));
        save(a("Third").status("Testing").createdAt(t0));

        var result = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "createdAt"))).getContent();

        assertThat(result).extracting(Assessment::getName).containsExactly("First", "Second", "Third");
    }

    @Test
    void sortsByStartDateAndPlannedEndDate() {
        var t0 = LocalDateTime.now();
        save(a("B").status("Testing").startDate(t0.minusDays(1)).plannedEndDate(t0.plusDays(2)));
        save(a("A").status("Testing").startDate(t0.minusDays(3)).plannedEndDate(t0.plusDays(1)));

        var byStart = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "startDate"))).getContent();
        assertThat(byStart).extracting(Assessment::getName).containsExactly("A", "B");

        var byEnd = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "plannedEndDate"))).getContent();
        assertThat(byEnd).extracting(Assessment::getName).containsExactly("A", "B");
    }

    @Test
    void sortPutsNullsLast_bothDirections() {
        save(a("HasDate").status("Testing").startDate(LocalDateTime.now().minusDays(1)));
        save(a("NoDate").status("Testing").startDate(null));

        var asc = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "startDate"))).getContent();
        assertThat(asc).extracting(Assessment::getName).containsExactly("HasDate", "NoDate");

        var desc = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "startDate"))).getContent();
        assertThat(desc).extracting(Assessment::getName).containsExactly("HasDate", "NoDate");
    }

    @Test
    void textSortIsCaseInsensitive() {
        // The database collates byte-wise ('Apple' < 'Zebra' < 'banana'), so an unfolded ORDER BY
        // would list every capitalized name before every lowercase one instead of interleaving.
        save(a("banana").status("Testing"));
        save(a("Apple").status("Testing"));
        save(a("cherry").status("Testing"));
        save(a("Zebra").status("Testing"));

        var result = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "name"))).getContent();

        assertThat(result).extracting(Assessment::getName)
                .containsExactly("Apple", "banana", "cherry", "Zebra");
    }

    // ── Sorting by a related entity's name (the table's Application / Type columns) ──

    @Test
    void sortsByApplicationName_notByApplicationId() {
        String alpha = application("app-z", "Alpha App");
        String zulu = application("app-a", "Zulu App");
        save(a("OnZulu").applicationId(zulu).status("Testing"));
        save(a("OnAlpha").applicationId(alpha).status("Testing"));

        var asc = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "applicationName"))).getContent();
        assertThat(asc).extracting(Assessment::getName).containsExactly("OnAlpha", "OnZulu");

        var desc = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "applicationName"))).getContent();
        assertThat(desc).extracting(Assessment::getName).containsExactly("OnZulu", "OnAlpha");
    }

    @Test
    void sortsByAssessmentTypeName() {
        String alpha = assessmentType("Alpha Type");
        String zulu = assessmentType("Zulu Type");
        save(a("OnZulu").assessmentTypeId(zulu).status("Testing"));
        save(a("OnAlpha").assessmentTypeId(alpha).status("Testing"));

        var result = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "assessmentTypeName"))).getContent();
        assertThat(result).extracting(Assessment::getName).containsExactly("OnAlpha", "OnZulu");
    }

    @Test
    void joinedSort_keepsRowsWhoseRelationIsMissing_andCountsThem() {
        // The joins are LEFT joins: an assessment pointing at a deleted/absent application must
        // still appear (nulls last) rather than being silently dropped from the page or the total.
        String appId = application("app-1", "Alpha App");
        save(a("HasApp").applicationId(appId).status("Testing"));
        save(a("NoApp").applicationId("app-missing").status("Testing"));

        var page = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "applicationName")));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(Assessment::getName).containsExactly("HasApp", "NoApp");
    }

    @Test
    void unknownSortKey_fallsBackToCreatedAtDesc_ratherThanFailing() {
        var t0 = LocalDateTime.now();
        save(a("Older").status("Testing").createdAt(t0.minusDays(1)));
        save(a("Newer").status("Testing").createdAt(t0));

        var result = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "somethingElse"))).getContent();
        assertThat(result).extracting(Assessment::getName).containsExactly("Newer", "Older");
    }

    // ── Pagination edges ────────────────────────────────────────────────────────

    @Test
    void pagination_partialLastPage() {
        for (int i = 0; i < 5; i++) save(a("A" + i).status("Testing"));

        var page = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(2, 2, Sort.by(Sort.Direction.ASC, "name")));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent()).extracting(Assessment::getName).containsExactly("A4");
    }

    @Test
    void pagination_beyondLastPage_isEmptyWithCorrectTotal() {
        for (int i = 0; i < 3; i++) save(a("A" + i).status("Testing"));

        var page = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(5, 2, Sort.by(Sort.Direction.ASC, "name")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).isEmpty();
    }

    @Test
    void unpaged_returnsAllMatches() {
        for (int i = 0; i < 5; i++) save(a("A" + i).status("Testing"));

        var page = assessmentRepository.searchAdvanced(base().build(), Pageable.unpaged());

        assertThat(page.getContent()).hasSize(5);
        assertThat(page.getTotalElements()).isEqualTo(5);
    }

    @Test
    void pagination_isStableWhenSortKeyTies() {
        // All rows share the sort key (name); without the a.id tiebreaker, LIMIT/OFFSET paging
        // could return a row on two pages or skip one, since tied rows are otherwise unordered.
        for (int i = 0; i < 6; i++) save(a("SameName").status("Testing"));
        var sort = Sort.by(Sort.Direction.ASC, "name");

        Set<String> seen = new java.util.HashSet<>();
        for (int p = 0; p < 3; p++) {
            assessmentRepository.searchAdvanced(base().build(), PageRequest.of(p, 2, sort)).getContent()
                    .forEach(x -> assertThat(seen.add(x.getId())).as("no row repeats across pages").isTrue());
        }
        assertThat(seen).hasSize(6); // every row seen exactly once — none duplicated or skipped
    }

    // ── Search edges ────────────────────────────────────────────────────────────

    @Test
    void search_blankIsIgnored_returnsAll() {
        save(a("One").status("Testing"));
        save(a("Two").status("Testing"));

        assertThat(search(base().search("").build())).hasSize(2);
        assertThat(search(base().search("   ").build())).hasSize(2);
    }

    @Test
    void search_noMatch_isEmpty() {
        save(a("Alpha").status("Testing"));
        assertThat(search(base().search("zzz").build())).isEmpty();
    }

    @Test
    void search_treatsPercentLiterally() {
        save(a("50% done").status("Testing"));
        save(a("50 done").status("Testing"));

        assertThat(search(base().search("50%").build())).extracting(Assessment::getName).containsExactly("50% done");
    }

    // ── Equality no-match ───────────────────────────────────────────────────────

    @Test
    void equalityFilters_noMatch_isEmpty() {
        save(a("A").applicationId("app-1").status("Testing"));

        assertThat(search(base().applicationId("app-does-not-exist").build())).isEmpty();
        assertThat(search(base().status("NA").build())).isEmpty();
    }

    // ── Severity depth (multiple, soft-deleted / unopened vuln excluded) ────────

    @Test
    void severities_matchAnyOfMultiple() {
        var h = save(a("HasHigh").status("Testing")).getId();
        var l = save(a("HasLow").status("Testing")).getId();
        var m = save(a("HasMedium").status("Testing")).getId();
        vuln(h, VulnerabilitySeverity.HIGH, LocalDateTime.now().minusDays(1));
        vuln(l, VulnerabilitySeverity.LOW, LocalDateTime.now().minusDays(1));
        vuln(m, VulnerabilitySeverity.MEDIUM, LocalDateTime.now().minusDays(1));

        var result = search(base().severityOrdinals(
                List.of(VulnerabilitySeverity.HIGH.ordinal(), VulnerabilitySeverity.LOW.ordinal())).build());

        assertThat(result).extracting(Assessment::getName).containsExactlyInAnyOrder("HasHigh", "HasLow");
    }

    @Test
    void severities_excludeSoftDeletedOrUnopenedVuln() {
        var now = LocalDateTime.now();
        var deleted = save(a("DeletedVuln").status("Testing")).getId();
        var unopened = save(a("UnopenedVuln").status("Testing")).getId();
        var ok = save(a("OpenVuln").status("Testing")).getId();
        vuln(deleted, VulnerabilitySeverity.HIGH, now.minusDays(1), now); // soft-deleted → excluded
        vuln(unopened, VulnerabilitySeverity.HIGH, null, null);           // opened_at null → excluded
        vuln(ok, VulnerabilitySeverity.HIGH, now.minusDays(1), null);

        var result = search(base().severityOrdinals(List.of(VulnerabilitySeverity.HIGH.ordinal())).build());

        assertThat(result).extracting(Assessment::getName).containsExactly("OpenVuln");
    }

    // ── Toggle "off" paths (filter not applied) ─────────────────────────────────

    @Test
    void pastDue_excludesNullPlannedEndDate() {
        save(a("NoEnd").status("Testing").plannedEndDate(null));
        assertThat(search(base().pastDue(true).build())).isEmpty();
    }

    @Test
    void excludeCompletedFalse_includesCompleted() {
        save(a("Active").status("Testing"));
        save(a("Done").status("Completed"));

        // excludeCompleted defaults to false → completed rows are included
        assertThat(search(base().build())).extracting(Assessment::getName)
                .containsExactlyInAnyOrder("Active", "Done");
    }

    @Test
    void assignedToMeFalse_doesNotFilter() {
        save(a("Mine").status("Testing").assessorId("me"));
        save(a("Theirs").status("Testing").assessorId("other"));

        // assignedToMe defaults to false → currentUserId is ignored, both returned
        assertThat(search(base().currentUserId("me").build())).hasSize(2);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private Assessment.AssessmentBuilder a(String name) {
        return Assessment.builder()
                .name(name)
                .assessmentTypeId("type-1")
                .organizationId("org-default")
                .createdAt(LocalDateTime.now());
    }

    /** Saves an application and returns its generated id, for use as an assessment's applicationId. */
    private String application(String appId, String name) {
        return applicationRepository.save(com.faction.clientportal.model.Application.builder()
                .appId(appId).name(name).createdAt(LocalDateTime.now()).build()).getId();
    }

    private String assessmentType(String name) {
        return assessmentTypeRepository.save(com.faction.clientportal.model.AssessmentType.builder()
                .name(name).createdAt(LocalDateTime.now()).build()).getId();
    }

    private Assessment save(Assessment.AssessmentBuilder b) {
        return assessmentRepository.save(b.build());
    }

    private void vuln(String assessmentId, VulnerabilitySeverity sev, LocalDateTime openedAt) {
        vuln(assessmentId, sev, openedAt, null);
    }

    private void vuln(String assessmentId, VulnerabilitySeverity sev, LocalDateTime openedAt, LocalDateTime deletedAt) {
        vulnerabilityRepository.save(Vulnerability.builder()
                .name("v-" + System.nanoTime())
                .severity(sev)
                .assessmentId(assessmentId)
                .order(0)
                .status("Open")
                .openedAt(openedAt)
                .deletedAt(deletedAt)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }
}
