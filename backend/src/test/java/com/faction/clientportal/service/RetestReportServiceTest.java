package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.RetestReportReadyDto;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.ReportTemplate;
import com.faction.clientportal.model.Retest;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.ReportTemplateRepository;
import com.faction.clientportal.repository.RetestRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.security.RequiresPermissionAuthorizationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which retests a retest report covers, which assessments are ready for one, and the evidence
 * lock a generated report leaves behind.
 */
@SpringBootTest
@ActiveProfiles("test")
class RetestReportServiceTest extends TestContainersConfig {

    @Autowired private RetestReportService retestReportService;
    @Autowired private RetestRepository retestRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private com.faction.clientportal.repository.VulnerabilityStageCompletionRepository stageCompletionRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private ReportTemplateRepository reportTemplateRepository;

    // Truncated so values survive the database round trip unchanged.
    private final LocalDateTime t = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    private User me;

    @BeforeEach
    void setUp() {
        retestRepository.deleteAll();
        stageCompletionRepository.deleteAll();
        vulnerabilityRepository.deleteAll();
        assessmentRepository.deleteAll();
        userRepository.deleteAll();
        me = user("me");
    }

    // ── latestCompletedByVulnerability ─────────────────────────────────────────

    @Test
    void latestCompletedPicksTheNewestPassOrFailAndIgnoresNewerCancelledOrOpen() {
        String asmt = assessment("A", null);
        String vuln = vulnerability(asmt, "SQLi");
        completed(asmt, vuln, "FAILED", t.minusDays(3), "me");
        Retest passed = completed(asmt, vuln, "PASSED", t.minusDays(1), "me");
        completed(asmt, vuln, "CANCELLED", t, "me");
        completed(asmt, vuln, "IN_PROGRESS", t, "me");

        var latest = retestReportService.latestCompletedByVulnerability(asmt);

        assertThat(latest).containsOnlyKeys(vuln);
        assertThat(latest.get(vuln).getStatus()).isEqualTo("PASSED");
        assertThat(latest.get(vuln).getId()).isEqualTo(passed.getId());
    }

    @Test
    void neverRetestedVulnerabilitiesAreAbsentFromTheMap() {
        String asmt = assessment("A", null);
        String retested = vulnerability(asmt, "Retested");
        String untouched = vulnerability(asmt, "Never retested");
        String onlyOpen = vulnerability(asmt, "Only an open retest");
        completed(asmt, retested, "FAILED", t, "me");
        completed(asmt, onlyOpen, "SCHEDULED", null, null);

        var latest = retestReportService.latestCompletedByVulnerability(asmt);

        assertThat(latest).containsOnlyKeys(retested);
        assertThat(latest).doesNotContainKeys(untouched, onlyOpen);
    }

    // ── readyForReport ─────────────────────────────────────────────────────────

    @Test
    void anAssessmentWithCompletedRetestsAndNoReportIsReady() {
        String appId = application("Payments");
        String asmt = assessmentRepository.save(Assessment.builder()
                .name("Payments Q3").assessmentTypeId("t").status("Testing").applicationId(appId)
                .createdAt(t).build()).getId();
        String vuln = vulnerability(asmt, "XSS");
        completed(asmt, vuln, "PASSED", t, null, me.getId());

        var rows = retestReportService.readyForReport("me", superAdmin());

        assertThat(rows).hasSize(1);
        RetestReportReadyDto row = rows.get(0);
        assertThat(row.getAssessmentId()).isEqualTo(asmt);
        assertThat(row.getAssessmentName()).isEqualTo("Payments Q3");
        assertThat(row.getApplicationId()).isEqualTo(appId);
        assertThat(row.getApplicationName()).isEqualTo("Payments");
        assertThat(row.getPassedCount()).isEqualTo(1);
        assertThat(row.getFailedCount()).isEqualTo(0);
        assertThat(row.getLastCompletedAt()).isEqualTo(t);
        assertThat(row.getRetestReportGeneratedAt()).isNull();
    }

    @Test
    void retestsCompletedBeforeTheLastReportDoNotMakeItReady() {
        String asmt = assessment("A", null);
        reportGeneratedAt(asmt, t);
        completed(asmt, vulnerability(asmt, "XSS"), "PASSED", t.minusHours(1), null, me.getId());

        assertThat(retestReportService.readyForReport("me", superAdmin())).isEmpty();
    }

    @Test
    void aRetestCompletedAfterTheLastReportMakesItReadyAndOnlyNewOnesAreCounted() {
        String asmt = assessment("A", null);
        reportGeneratedAt(asmt, t.minusHours(1));
        completed(asmt, vulnerability(asmt, "Old"), "PASSED", t.minusHours(2), null, me.getId());
        completed(asmt, vulnerability(asmt, "New"), "FAILED", t, null, me.getId());

        var rows = retestReportService.readyForReport("me", superAdmin());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getPassedCount()).isEqualTo(0);
        assertThat(rows.get(0).getFailedCount()).isEqualTo(1);
        assertThat(rows.get(0).getLastCompletedAt()).isEqualTo(t);
        assertThat(rows.get(0).getRetestReportGeneratedAt()).isEqualTo(t.minusHours(1));
    }

    @Test
    void evidenceEditedAfterTheReportMakesItReadyAgain() {
        String asmt = assessment("A", null);
        reportGeneratedAt(asmt, t.minusHours(1));
        Retest retest = completed(asmt, vulnerability(asmt, "XSS"), "PASSED", t.minusHours(2), null, me.getId());
        retest.setEvidenceUpdatedAt(t);
        retestRepository.save(retest);

        var rows = retestReportService.readyForReport("me", superAdmin());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getPassedCount()).isEqualTo(1);
        assertThat(rows.get(0).getFailedCount()).isEqualTo(0);
    }

    @Test
    void cancelledAndOpenRetestsNeverCount() {
        String asmt = assessment("A", null);
        for (String status : List.of("CANCELLED", "SCHEDULED", "IN_PROGRESS", "REQUESTED")) {
            completed(asmt, vulnerability(asmt, status), status, t, "me", me.getId());
        }

        assertThat(retestReportService.readyForReport("me", superAdmin())).isEmpty();
    }

    @Test
    void retestsOnDeletedVulnerabilitiesDoNotCount() {
        String asmt = assessment("A", null);
        reportGeneratedAt(asmt, t.minusHours(1));
        String vuln = vulnerability(asmt, "Gone");
        Vulnerability v = vulnerabilityRepository.findById(vuln).orElseThrow();
        v.setDeletedAt(t);
        vulnerabilityRepository.save(v);
        completed(asmt, vuln, "PASSED", t, "me", me.getId());

        assertThat(retestReportService.readyForReport("me", superAdmin())).isEmpty();
        assertThat(retestReportService.latestCompletedByVulnerability(asmt)).isEmpty();
    }

    @Test
    void onlyAssessmentsWhereIAmAssignedOrCompletedByShowUp() {
        User other = user("other");
        String a = assessment("A", null);
        String b = assessment("B", null);
        String c = assessment("C", null);
        completed(a, vulnerability(a, "a"), "PASSED", t, "other", me.getId());
        completed(b, vulnerability(b, "b"), "FAILED", t.minusMinutes(5), "me", other.getId());
        completed(c, vulnerability(c, "c"), "PASSED", t, "other", other.getId());

        var rows = retestReportService.readyForReport("me", superAdmin());

        assertThat(rows).extracting(RetestReportReadyDto::getAssessmentId).containsExactly(a, b);
    }

    @Test
    void countsCoverEveryNewRetestOnTheAssessmentNotOnlyMine() {
        User other = user("other");
        String asmt = assessment("A", null);
        reportGeneratedAt(asmt, t.minusHours(1));
        completed(asmt, vulnerability(asmt, "mine"), "PASSED", t.minusMinutes(30), "me", me.getId());
        completed(asmt, vulnerability(asmt, "theirs"), "FAILED", t, "other", other.getId());

        var rows = retestReportService.readyForReport("me", superAdmin());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getPassedCount()).isEqualTo(1);
        assertThat(rows.get(0).getFailedCount()).isEqualTo(1);
        assertThat(rows.get(0).getLastCompletedAt()).isEqualTo(t);
    }

    @Test
    void anOldRetestOfMineWithOnlySomeoneElsesNewOneIsNotMyRow() {
        User other = user("other");
        String asmt = assessment("A", null);
        reportGeneratedAt(asmt, t.minusHours(1));
        completed(asmt, vulnerability(asmt, "mine"), "PASSED", t.minusHours(2), null, me.getId());
        completed(asmt, vulnerability(asmt, "theirs"), "FAILED", t, "other", other.getId());

        assertThat(retestReportService.readyForReport("me", superAdmin())).isEmpty();
        assertThat(retestReportService.readyForReport("other", superAdmin()))
                .extracting(RetestReportReadyDto::getAssessmentId).containsExactly(asmt);
    }

    @Test
    void readinessRespectsAccessScope() {
        me.setTeamIds(List.of("team-a"));
        userRepository.save(me);
        String ours = assessment("Ours", "team-a");
        String theirs = assessment("Theirs", "team-b");
        completed(ours, vulnerability(ours, "ours"), "PASSED", t, null, me.getId());
        completed(theirs, vulnerability(theirs, "theirs"), "PASSED", t, null, me.getId());

        var rows = retestReportService.readyForReport("me", auth("me",
                Permission.ASSESSMENTS_READ_TEAM.getPermission(),
                Permission.VULNERABILITIES_READ_TEAM.getPermission()));

        assertThat(rows).extracting(RetestReportReadyDto::getAssessmentId).containsExactly(ours);
    }

    @Test
    void hasRetestTemplateReflectsTheLiveTemplate() {
        String withRetest = template("retest-file-" + UUID.randomUUID());
        String without = template(null);
        String a = assessmentRepository.save(Assessment.builder()
                .name("With").assessmentTypeId("t").status("Testing").reportTemplateId(withRetest)
                .createdAt(t).build()).getId();
        String b = assessmentRepository.save(Assessment.builder()
                .name("Without").assessmentTypeId("t").status("Testing").reportTemplateId(without)
                .createdAt(t).build()).getId();
        // A type with no templates at all, so there is nothing for "No template" to fall back to.
        String c = assessmentRepository.save(Assessment.builder()
                .name("No template").assessmentTypeId("type-without-templates").status("Testing")
                .createdAt(t).build()).getId();
        completed(a, vulnerability(a, "a"), "PASSED", t, null, me.getId());
        completed(b, vulnerability(b, "b"), "PASSED", t.minusMinutes(1), null, me.getId());
        completed(c, vulnerability(c, "c"), "PASSED", t.minusMinutes(2), null, me.getId());

        var rows = retestReportService.readyForReport("me", superAdmin());

        assertThat(rows).extracting(RetestReportReadyDto::getAssessmentId).containsExactly(a, b, c);
        assertThat(rows).extracting(RetestReportReadyDto::isHasRetestTemplate).containsExactly(true, false, false);
        assertThat(retestReportService.hasRetestTemplate(assessmentRepository.findById(a).orElseThrow())).isTrue();
        assertThat(retestReportService.hasRetestTemplate(assessmentRepository.findById(c).orElseThrow())).isFalse();
    }

    @Test
    void anAssessmentOnADeletedTemplateUsesTheNewestLiveTemplateOfItsType() {
        String type = "type-" + UUID.randomUUID();
        String retired = reportTemplateRepository.save(ReportTemplate.builder()
                .name("Retired " + UUID.randomUUID()).assessmentTypeId(type)
                .version(1).active(false).deletedAt(t).userDefinedFields(new java.util.ArrayList<>())
                .createdAt(t.minusDays(30)).updatedAt(t.minusDays(30)).build()).getId();
        reportTemplateRepository.save(ReportTemplate.builder()
                .name("Older live " + UUID.randomUUID()).assessmentTypeId(type)
                .version(1).active(true).userDefinedFields(new java.util.ArrayList<>())
                .createdAt(t.minusDays(10)).updatedAt(t.minusDays(10)).build());
        reportTemplateRepository.save(ReportTemplate.builder()
                .name("Current " + UUID.randomUUID()).assessmentTypeId(type)
                .version(1).active(true).userDefinedFields(new java.util.ArrayList<>())
                .retestTemplateFileId("retest-file-" + UUID.randomUUID())
                .createdAt(t.minusDays(1)).updatedAt(t.minusDays(1)).build());
        Assessment onRetired = assessmentRepository.save(Assessment.builder()
                .name("On retired template").assessmentTypeId(type).status("Testing")
                .reportTemplateId(retired).createdAt(t).build());

        assertThat(retestReportService.hasRetestTemplate(onRetired)).isTrue();
    }

    // ── untestedCounts ─────────────────────────────────────────────────────────

    @Test
    void untestedCountsFindingsWithNoPassedOrFailedRetest() {
        String asmt = assessment("A", null);
        completed(asmt, vulnerability(asmt, "passed"), "PASSED", t, "me");
        completed(asmt, vulnerability(asmt, "failed"), "FAILED", t, "me");
        completed(asmt, vulnerability(asmt, "cancelled only"), "CANCELLED", t, "me");
        vulnerability(asmt, "never retested");
        String deleted = vulnerability(asmt, "deleted");
        Vulnerability d = vulnerabilityRepository.findById(deleted).orElseThrow();
        d.setDeletedAt(t);
        vulnerabilityRepository.save(d);

        var counts = retestReportService.untestedCounts(List.of(asmt), superAdmin());

        // "cancelled only" and "never retested"; the deleted finding doesn't count.
        assertThat(counts).containsEntry(asmt, 2);
    }

    @Test
    void untestedCountsSkipAssessmentsOutsideTheCallersScope() {
        me.setTeamIds(List.of("team-a"));
        userRepository.save(me);
        String ours = assessment("Ours", "team-a");
        String theirs = assessment("Theirs", "team-b");
        vulnerability(ours, "ours");
        vulnerability(theirs, "theirs");

        var counts = retestReportService.untestedCounts(List.of(ours, theirs), auth("me",
                Permission.ASSESSMENTS_READ_TEAM.getPermission(),
                Permission.VULNERABILITIES_READ_TEAM.getPermission()));

        assertThat(counts).containsOnlyKeys(ours).containsEntry(ours, 1);
    }

    // ── recordGeneration ───────────────────────────────────────────────────────

    @Test
    void recordGenerationStampsTheStartTimeAndLocksOnlyTheGivenRetests() {
        String asmt = assessment("A", null);
        Retest r1 = completed(asmt, vulnerability(asmt, "one"), "PASSED", t, "me");
        Retest r2 = completed(asmt, vulnerability(asmt, "two"), "FAILED", t, "me");
        LocalDateTime startedAt = t.minusMinutes(1);

        retestReportService.recordGeneration(asmt, startedAt, List.of(r1.getId()));

        assertThat(assessmentRepository.findById(asmt).orElseThrow().getRetestReportGeneratedAt())
                .isEqualTo(startedAt);
        LocalDateTime firstLock = retestRepository.findById(r1.getId()).orElseThrow().getEvidenceLockedAt();
        assertThat(firstLock).isNotNull();
        assertThat(retestRepository.findById(r2.getId()).orElseThrow().getEvidenceLockedAt()).isNull();

        LocalDateTime secondStart = t.plusMinutes(5);
        retestReportService.recordGeneration(asmt, secondStart, List.of(r1.getId()));

        assertThat(assessmentRepository.findById(asmt).orElseThrow().getRetestReportGeneratedAt())
                .isEqualTo(secondStart);
        assertThat(retestRepository.findById(r1.getId()).orElseThrow().getEvidenceLockedAt())
                .isEqualTo(firstLock);
        assertThat(retestRepository.findById(r2.getId()).orElseThrow().getEvidenceLockedAt()).isNull();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Saves a retest in the given state; {@code completedBy} is a username, assessors are user ids. */
    private Retest completed(String assessmentId, String vulnId, String status, LocalDateTime closedDate,
                             String completedBy, String... assessorIds) {
        return retestRepository.save(Retest.builder()
                .vulnerabilityId(vulnId).assessmentId(assessmentId).status(status)
                .closedDate(closedDate).completedBy(completedBy)
                .assignedAssessorIds(new java.util.ArrayList<>(List.of(assessorIds)))
                .createdBy("system").lastUpdatedBy("system")
                .scheduledStartDate(t.minusDays(5)).scheduledEndDate(t.plusDays(1))
                .createdAt(t.minusDays(5)).updatedAt(t).build());
    }

    private String assessment(String name, String teamId) {
        return assessmentRepository.save(Assessment.builder()
                .name(name).assessmentTypeId("t").status("Testing").teamId(teamId)
                .createdAt(t).build()).getId();
    }

    private void reportGeneratedAt(String assessmentId, LocalDateTime at) {
        Assessment a = assessmentRepository.findById(assessmentId).orElseThrow();
        a.setRetestReportGeneratedAt(at);
        assessmentRepository.save(a);
    }

    private String vulnerability(String assessmentId, String name) {
        return vulnerabilityRepository.save(Vulnerability.builder()
                .name(name).assessmentId(assessmentId).severity(VulnerabilitySeverity.HIGH).order(0)
                .status("Open").openedAt(t).createdAt(t).updatedAt(t).build()).getId();
    }

    private String application(String name) {
        return applicationRepository.save(Application.builder()
                .appId("APP-" + UUID.randomUUID()).name(name)
                .createdAt(t).build()).getId();
    }

    private String template(String retestTemplateFileId) {
        return reportTemplateRepository.save(ReportTemplate.builder()
                .name("Retest template " + UUID.randomUUID()).assessmentTypeId("t")
                .version(1).active(true).userDefinedFields(new java.util.ArrayList<>())
                .retestTemplateFileId(retestTemplateFileId)
                .createdAt(t).updatedAt(t).build()).getId();
    }

    private User user(String username) {
        return userRepository.save(User.builder()
                .username(username).firstName("T").lastName("U").email(username + "@test.com")
                .password("x").loginOption(LoginOption.NATIVE).teamIds(List.of())
                .isInternal(true).failedLoginAttempts(0).createdAt(LocalDateTime.now()).build());
    }

    private Authentication superAdmin() {
        return auth("me", RequiresPermissionAuthorizationManager.SUPER_ADMIN);
    }

    private Authentication auth(String username, String... authorities) {
        List<GrantedAuthority> granted = Arrays.stream(authorities)
                .map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a)).toList();
        return new UsernamePasswordAuthenticationToken(username, null, granted);
    }
}
