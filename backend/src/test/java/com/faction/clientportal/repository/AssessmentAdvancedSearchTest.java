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

    private static final Set<String> COMPLETED = Set.of("COMPLETED", "APPROVED", "ARCHIVED");
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
                .completedStatuses(COMPLETED)
                .now(LocalDateTime.now());
    }

    private List<Assessment> search(AssessmentSearchCriteria c) {
        return assessmentRepository.searchAdvanced(c, PAGE).getContent();
    }

    // ── Baseline / deleted / pagination ────────────────────────────────────────

    @Test
    void excludesSoftDeleted_andCountsTotal() {
        save(a("Live").status("IN_PROGRESS"));
        save(a("Gone").status("IN_PROGRESS").deletedAt(LocalDateTime.now()));

        var page = assessmentRepository.searchAdvanced(base().build(), PAGE);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(Assessment::getName).containsExactly("Live");
    }

    @Test
    void paginates_withCorrectTotal() {
        for (int i = 0; i < 5; i++) save(a("A" + i).status("IN_PROGRESS"));

        var page = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(1, 2, Sort.by(Sort.Direction.ASC, "name")));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent()).extracting(Assessment::getName).containsExactly("A2", "A3");
    }

    // ── Search (name substring, case-insensitive, literal wildcards) ────────────

    @Test
    void search_isCaseInsensitiveSubstring() {
        save(a("Web App Pentest").status("IN_PROGRESS"));
        save(a("Mobile Review").status("IN_PROGRESS"));

        assertThat(search(base().search("web").build())).extracting(Assessment::getName).containsExactly("Web App Pentest");
        assertThat(search(base().search("PENTEST").build())).extracting(Assessment::getName).containsExactly("Web App Pentest");
    }

    @Test
    void search_treatsWildcardsLiterally() {
        save(a("app_01").status("IN_PROGRESS"));
        save(a("appX01").status("IN_PROGRESS"));

        // '_' must match literally, not as a single-char wildcard.
        assertThat(search(base().search("app_0").build())).extracting(Assessment::getName).containsExactly("app_01");
    }

    // ── Equality filters ────────────────────────────────────────────────────────

    @Test
    void filtersByApplicationOrgTypeCampaign() {
        save(a("Match").applicationId("app-1").organizationId("org-1").assessmentTypeId("t-1").campaignId("c-1").status("IN_PROGRESS"));
        save(a("Other").applicationId("app-2").organizationId("org-2").assessmentTypeId("t-2").campaignId("c-2").status("IN_PROGRESS"));

        assertThat(search(base().applicationId("app-1").build())).extracting(Assessment::getName).containsExactly("Match");
        assertThat(search(base().organizationId("org-1").build())).extracting(Assessment::getName).containsExactly("Match");
        assertThat(search(base().assessmentTypeId("t-1").build())).extracting(Assessment::getName).containsExactly("Match");
        assertThat(search(base().campaignId("c-1").build())).extracting(Assessment::getName).containsExactly("Match");
    }

    @Test
    void status_isCaseInsensitive() {
        save(a("A").status("In_Progress"));
        assertThat(search(base().status("IN_PROGRESS").build())).extracting(Assessment::getName).containsExactly("A");
    }

    // ── Owned scope ────────────────────────────────────────────────────────────

    @Test
    void ownedScope_restrictsToApps_andEmptyMatchesNothing() {
        save(a("Owned").applicationId("app-1").status("IN_PROGRESS"));
        save(a("NotOwned").applicationId("app-9").status("IN_PROGRESS"));

        assertThat(search(base().ownedAppIds(List.of("app-1")).build())).extracting(Assessment::getName).containsExactly("Owned");
        assertThat(search(base().ownedAppIds(List.of()).build())).isEmpty(); // empty owned → nothing
    }

    @Test
    void applicationIds_restrictsToSelectedApps_emptyMeansNoFilter() {
        save(a("A1").applicationId("app-1").status("IN_PROGRESS"));
        save(a("A2").applicationId("app-2").status("IN_PROGRESS"));
        save(a("A3").applicationId("app-3").status("IN_PROGRESS"));

        assertThat(search(base().applicationIds(List.of("app-1", "app-3")).build()))
                .extracting(Assessment::getName).containsExactlyInAnyOrder("A1", "A3");
        // Empty multi-app filter is "no filter" (unlike the owned scope, which matches nothing).
        assertThat(search(base().applicationIds(List.of()).build())).hasSize(3);
    }

    // ── Date ranges (an active date filter excludes out-of-range AND undated rows) ──

    @Test
    void startDateRange_excludesOutOfRangeAndUndated() {
        var now = LocalDateTime.now();
        save(a("InRange").status("IN_PROGRESS").startDate(now.minusDays(1)));
        save(a("TooEarly").status("IN_PROGRESS").startDate(now.minusDays(30)));
        save(a("NullStart").status("IN_PROGRESS").startDate(null));

        var result = search(base().startDateFrom(now.minusDays(5)).startDateTo(now).build());

        assertThat(result).extracting(Assessment::getName).containsExactly("InRange");
    }

    // ── Completed / past due ────────────────────────────────────────────────────

    @Test
    void excludeCompleted_dropsCompletedStatuses_butKeepsNullStatus() {
        save(a("Active").status("IN_PROGRESS"));
        save(a("Done").status("COMPLETED"));
        save(a("NoStatus").status(null));

        var result = search(base().excludeCompleted(true).build());

        assertThat(result).extracting(Assessment::getName).containsExactlyInAnyOrder("Active", "NoStatus");
    }

    @Test
    void pastDue_onlyOverdueNonCompleted() {
        var now = LocalDateTime.now();
        save(a("Overdue").status("IN_PROGRESS").plannedEndDate(now.minusDays(1)));
        save(a("Future").status("IN_PROGRESS").plannedEndDate(now.plusDays(1)));
        save(a("OverdueButDone").status("COMPLETED").plannedEndDate(now.minusDays(1)));

        var result = search(base().pastDue(true).build());

        assertThat(result).extracting(Assessment::getName).containsExactly("Overdue");
    }

    // ── Assigned-to-me (managers, legacy assessor, assessorIds JSONB) ───────────

    @Test
    void assignedToMe_matchesManagersLegacyAndAssessorIds() {
        save(a("ByEngMgr").status("IN_PROGRESS").engagementManagerId("me"));
        save(a("ByRemMgr").status("IN_PROGRESS").remediationManagerId("me"));
        save(a("ByLegacy").status("IN_PROGRESS").assessorId("me"));
        save(a("ByAssessorIds").status("IN_PROGRESS").assessorIds(List.of("x", "me")));
        save(a("NotMine").status("IN_PROGRESS").assessorIds(List.of("someone")));

        var result = search(base().assignedToMe(true).currentUserId("me").build());

        assertThat(result).extracting(Assessment::getName)
                .containsExactlyInAnyOrder("ByEngMgr", "ByRemMgr", "ByLegacy", "ByAssessorIds");
    }

    @Test
    void assessorId_matchesLegacyOrAssessorIds() {
        save(a("Legacy").status("IN_PROGRESS").assessorId("u1"));
        save(a("InArray").status("IN_PROGRESS").assessorIds(List.of("u1")));
        save(a("Neither").status("IN_PROGRESS").assessorIds(List.of("u2")));

        assertThat(search(base().assessorId("u1").build())).extracting(Assessment::getName)
                .containsExactlyInAnyOrder("Legacy", "InArray");
    }

    // ── Team membership (assessorIds ∩ members) ─────────────────────────────────

    @Test
    void teamMembers_intersectAssessorIds_andEmptyMatchesNothing() {
        save(a("HasMember").status("IN_PROGRESS").assessorIds(List.of("u1", "u3")));
        save(a("NoMember").status("IN_PROGRESS").assessorIds(List.of("u9")));

        assertThat(search(base().teamMemberIds(List.of("u1", "u2")).build())).extracting(Assessment::getName).containsExactly("HasMember");
        assertThat(search(base().teamMemberIds(List.of()).build())).isEmpty(); // team with no members → nothing
    }

    // ── Severity (EXISTS opened vuln of severity within date range) ─────────────

    @Test
    void severities_keepAssessmentsWithMatchingOpenedVuln() {
        var withHigh = save(a("HasHigh").status("IN_PROGRESS")).getId();
        var withLow = save(a("HasLow").status("IN_PROGRESS")).getId();
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
        var a1 = save(a("Recent").status("IN_PROGRESS").startDate(now.minusDays(1))).getId();
        var a2 = save(a("Old").status("IN_PROGRESS").startDate(now.minusDays(1))).getId();
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
        save(a("Alpha").status("IN_PROGRESS"));
        save(a("Charlie").status("IN_PROGRESS"));
        save(a("Bravo").status("IN_PROGRESS"));

        var result = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "name"))).getContent();

        assertThat(result).extracting(Assessment::getName).containsExactly("Charlie", "Bravo", "Alpha");
    }

    // ── End-date range (mirror of start-date; a date filter excludes undated) ────

    @Test
    void endDateRange_excludesOutOfRangeAndUndated() {
        var now = LocalDateTime.now();
        save(a("InRange").status("IN_PROGRESS").plannedEndDate(now.minusDays(1)));
        save(a("TooEarly").status("IN_PROGRESS").plannedEndDate(now.minusDays(30)));
        save(a("NullEnd").status("IN_PROGRESS").plannedEndDate(null));

        var result = search(base().endDateFrom(now.minusDays(5)).endDateTo(now).build());

        assertThat(result).extracting(Assessment::getName).containsExactly("InRange");
    }

    // ── Filter combinations (AND semantics — what the UX actually issues) ────────

    @Test
    void combinesFilters_appStatusAndDateNarrowTogether() {
        var now = LocalDateTime.now();
        save(a("Target").applicationId("app-1").status("IN_PROGRESS").startDate(now.minusDays(1)));
        save(a("WrongApp").applicationId("app-2").status("IN_PROGRESS").startDate(now.minusDays(1)));
        save(a("WrongStatus").applicationId("app-1").status("COMPLETED").startDate(now.minusDays(1)));
        save(a("WrongDate").applicationId("app-1").status("IN_PROGRESS").startDate(now.minusDays(60)));

        var result = search(base().applicationId("app-1").status("IN_PROGRESS")
                .startDateFrom(now.minusDays(5)).startDateTo(now).build());

        assertThat(result).extracting(Assessment::getName).containsExactly("Target");
    }

    @Test
    void combinesFilters_ownedScopePlusStatus() {
        save(a("Keep").applicationId("app-1").status("IN_PROGRESS"));
        save(a("WrongStatus").applicationId("app-1").status("COMPLETED"));
        save(a("Unowned").applicationId("app-9").status("IN_PROGRESS"));

        var result = search(base().ownedAppIds(List.of("app-1")).status("IN_PROGRESS").build());

        assertThat(result).extracting(Assessment::getName).containsExactly("Keep");
    }

    @Test
    void combinesFilters_searchPlusOrg() {
        save(a("Web Test").organizationId("org-1").status("IN_PROGRESS"));
        save(a("Web Test").organizationId("org-2").status("IN_PROGRESS")); // same name, other org
        save(a("Mobile Test").organizationId("org-1").status("IN_PROGRESS"));

        var result = search(base().search("web").organizationId("org-1").build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOrganizationId()).isEqualTo("org-1");
    }

    @Test
    void combinesFilters_severityDateWindowAndStatus() {
        var now = LocalDateTime.now();
        var keep = save(a("Keep").status("IN_PROGRESS").startDate(now.minusDays(1))).getId();
        var wrongStatus = save(a("WrongStatus").status("COMPLETED").startDate(now.minusDays(1))).getId();
        var wrongWindow = save(a("WrongWindow").status("IN_PROGRESS").startDate(now.minusDays(1))).getId();
        vuln(keep, VulnerabilitySeverity.HIGH, now.minusDays(1));
        vuln(wrongStatus, VulnerabilitySeverity.HIGH, now.minusDays(1));
        vuln(wrongWindow, VulnerabilitySeverity.HIGH, now.minusDays(60));

        var result = search(base().severityOrdinals(List.of(VulnerabilitySeverity.HIGH.ordinal()))
                .status("IN_PROGRESS").startDateFrom(now.minusDays(5)).startDateTo(now).build());

        assertThat(result).extracting(Assessment::getName).containsExactly("Keep");
    }

    // ── Sorting (fields, directions, default, NULLS LAST) ───────────────────────

    @Test
    void unsorted_defaultsToCreatedAtDescending() {
        var t0 = LocalDateTime.now();
        save(a("First").status("IN_PROGRESS").createdAt(t0.minusDays(2)));
        save(a("Second").status("IN_PROGRESS").createdAt(t0.minusDays(1)));
        save(a("Third").status("IN_PROGRESS").createdAt(t0));

        var result = assessmentRepository.searchAdvanced(base().build(), PageRequest.of(0, 50)).getContent();

        assertThat(result).extracting(Assessment::getName).containsExactly("Third", "Second", "First");
    }

    @Test
    void sortsByCreatedAtAscending() {
        var t0 = LocalDateTime.now();
        save(a("First").status("IN_PROGRESS").createdAt(t0.minusDays(2)));
        save(a("Second").status("IN_PROGRESS").createdAt(t0.minusDays(1)));
        save(a("Third").status("IN_PROGRESS").createdAt(t0));

        var result = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "createdAt"))).getContent();

        assertThat(result).extracting(Assessment::getName).containsExactly("First", "Second", "Third");
    }

    @Test
    void sortsByStartDateAndPlannedEndDate() {
        var t0 = LocalDateTime.now();
        save(a("B").status("IN_PROGRESS").startDate(t0.minusDays(1)).plannedEndDate(t0.plusDays(2)));
        save(a("A").status("IN_PROGRESS").startDate(t0.minusDays(3)).plannedEndDate(t0.plusDays(1)));

        var byStart = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "startDate"))).getContent();
        assertThat(byStart).extracting(Assessment::getName).containsExactly("A", "B");

        var byEnd = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "plannedEndDate"))).getContent();
        assertThat(byEnd).extracting(Assessment::getName).containsExactly("A", "B");
    }

    @Test
    void sortPutsNullsLast_bothDirections() {
        save(a("HasDate").status("IN_PROGRESS").startDate(LocalDateTime.now().minusDays(1)));
        save(a("NoDate").status("IN_PROGRESS").startDate(null));

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
        save(a("banana").status("IN_PROGRESS"));
        save(a("Apple").status("IN_PROGRESS"));
        save(a("cherry").status("IN_PROGRESS"));
        save(a("Zebra").status("IN_PROGRESS"));

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
        save(a("OnZulu").applicationId(zulu).status("IN_PROGRESS"));
        save(a("OnAlpha").applicationId(alpha).status("IN_PROGRESS"));

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
        save(a("OnZulu").assessmentTypeId(zulu).status("IN_PROGRESS"));
        save(a("OnAlpha").assessmentTypeId(alpha).status("IN_PROGRESS"));

        var result = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "assessmentTypeName"))).getContent();
        assertThat(result).extracting(Assessment::getName).containsExactly("OnAlpha", "OnZulu");
    }

    @Test
    void joinedSort_keepsRowsWhoseRelationIsMissing_andCountsThem() {
        // The joins are LEFT joins: an assessment pointing at a deleted/absent application must
        // still appear (nulls last) rather than being silently dropped from the page or the total.
        String appId = application("app-1", "Alpha App");
        save(a("HasApp").applicationId(appId).status("IN_PROGRESS"));
        save(a("NoApp").applicationId("app-missing").status("IN_PROGRESS"));

        var page = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "applicationName")));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(Assessment::getName).containsExactly("HasApp", "NoApp");
    }

    @Test
    void unknownSortKey_fallsBackToCreatedAtDesc_ratherThanFailing() {
        var t0 = LocalDateTime.now();
        save(a("Older").status("IN_PROGRESS").createdAt(t0.minusDays(1)));
        save(a("Newer").status("IN_PROGRESS").createdAt(t0));

        var result = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "somethingElse"))).getContent();
        assertThat(result).extracting(Assessment::getName).containsExactly("Newer", "Older");
    }

    // ── Pagination edges ────────────────────────────────────────────────────────

    @Test
    void pagination_partialLastPage() {
        for (int i = 0; i < 5; i++) save(a("A" + i).status("IN_PROGRESS"));

        var page = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(2, 2, Sort.by(Sort.Direction.ASC, "name")));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent()).extracting(Assessment::getName).containsExactly("A4");
    }

    @Test
    void pagination_beyondLastPage_isEmptyWithCorrectTotal() {
        for (int i = 0; i < 3; i++) save(a("A" + i).status("IN_PROGRESS"));

        var page = assessmentRepository.searchAdvanced(base().build(),
                PageRequest.of(5, 2, Sort.by(Sort.Direction.ASC, "name")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).isEmpty();
    }

    @Test
    void unpaged_returnsAllMatches() {
        for (int i = 0; i < 5; i++) save(a("A" + i).status("IN_PROGRESS"));

        var page = assessmentRepository.searchAdvanced(base().build(), Pageable.unpaged());

        assertThat(page.getContent()).hasSize(5);
        assertThat(page.getTotalElements()).isEqualTo(5);
    }

    @Test
    void pagination_isStableWhenSortKeyTies() {
        // All rows share the sort key (name); without the a.id tiebreaker, LIMIT/OFFSET paging
        // could return a row on two pages or skip one, since tied rows are otherwise unordered.
        for (int i = 0; i < 6; i++) save(a("SameName").status("IN_PROGRESS"));
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
        save(a("One").status("IN_PROGRESS"));
        save(a("Two").status("IN_PROGRESS"));

        assertThat(search(base().search("").build())).hasSize(2);
        assertThat(search(base().search("   ").build())).hasSize(2);
    }

    @Test
    void search_noMatch_isEmpty() {
        save(a("Alpha").status("IN_PROGRESS"));
        assertThat(search(base().search("zzz").build())).isEmpty();
    }

    @Test
    void search_treatsPercentLiterally() {
        save(a("50% done").status("IN_PROGRESS"));
        save(a("50 done").status("IN_PROGRESS"));

        assertThat(search(base().search("50%").build())).extracting(Assessment::getName).containsExactly("50% done");
    }

    // ── Equality no-match ───────────────────────────────────────────────────────

    @Test
    void equalityFilters_noMatch_isEmpty() {
        save(a("A").applicationId("app-1").status("IN_PROGRESS"));

        assertThat(search(base().applicationId("app-does-not-exist").build())).isEmpty();
        assertThat(search(base().status("ARCHIVED").build())).isEmpty();
    }

    // ── Severity depth (multiple, soft-deleted / unopened vuln excluded) ────────

    @Test
    void severities_matchAnyOfMultiple() {
        var h = save(a("HasHigh").status("IN_PROGRESS")).getId();
        var l = save(a("HasLow").status("IN_PROGRESS")).getId();
        var m = save(a("HasMedium").status("IN_PROGRESS")).getId();
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
        var deleted = save(a("DeletedVuln").status("IN_PROGRESS")).getId();
        var unopened = save(a("UnopenedVuln").status("IN_PROGRESS")).getId();
        var ok = save(a("OpenVuln").status("IN_PROGRESS")).getId();
        vuln(deleted, VulnerabilitySeverity.HIGH, now.minusDays(1), now); // soft-deleted → excluded
        vuln(unopened, VulnerabilitySeverity.HIGH, null, null);           // opened_at null → excluded
        vuln(ok, VulnerabilitySeverity.HIGH, now.minusDays(1), null);

        var result = search(base().severityOrdinals(List.of(VulnerabilitySeverity.HIGH.ordinal())).build());

        assertThat(result).extracting(Assessment::getName).containsExactly("OpenVuln");
    }

    // ── Toggle "off" paths (filter not applied) ─────────────────────────────────

    @Test
    void pastDue_excludesNullPlannedEndDate() {
        save(a("NoEnd").status("IN_PROGRESS").plannedEndDate(null));
        assertThat(search(base().pastDue(true).build())).isEmpty();
    }

    @Test
    void excludeCompletedFalse_includesCompleted() {
        save(a("Active").status("IN_PROGRESS"));
        save(a("Done").status("COMPLETED"));

        // excludeCompleted defaults to false → completed rows are included
        assertThat(search(base().build())).extracting(Assessment::getName)
                .containsExactlyInAnyOrder("Active", "Done");
    }

    @Test
    void assignedToMeFalse_doesNotFilter() {
        save(a("Mine").status("IN_PROGRESS").assessorId("me"));
        save(a("Theirs").status("IN_PROGRESS").assessorId("other"));

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
