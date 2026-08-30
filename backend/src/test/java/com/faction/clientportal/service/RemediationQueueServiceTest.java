package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentWorkflowConfig;
import com.faction.clientportal.model.AssessmentWorkflowConfig.VulnerabilitySla;
import com.faction.clientportal.model.Retest;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentWorkflowConfigRepository;
import com.faction.clientportal.repository.RetestRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The remediation queue badge ({@code RemediationQueueService.queueCount}): open tracked
 * vulnerabilities at or past their SLA <em>warning</em> threshold, plus requested/scheduled/
 * in-progress retests. Both halves are single aggregates, so this exercises the real SQL.
 *
 * <p>The core math: a vuln's warning threshold is {@code pastDueDays - warningDays} days, and it
 * qualifies when {@code openedAt::date + threshold <= current_date} — i.e. opened at least
 * {@code (pastDueDays - warningDays)} days ago. These tests pin that boundary exactly (the UI
 * only shows "99+", so the assertions are the real oracle), plus per-severity independence and
 * every exclusion rule.
 */
@SpringBootTest
@ActiveProfiles("test")
class RemediationQueueServiceTest extends TestContainersConfig {

    @Autowired private RemediationQueueService service;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private RetestRepository retestRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private AssessmentWorkflowConfigRepository workflowConfigRepository;

    private String liveAssessmentId;

    @BeforeEach
    void setUp() {
        vulnerabilityRepository.deleteAll();
        retestRepository.deleteAll();
        assessmentRepository.deleteAll();
        workflowConfigRepository.deleteAll();

        // Full realistic SLA set (matches the running instance). Warning thresholds
        // (pastDueDays - warningDays): CRITICAL 4, HIGH 15, MEDIUM 45, LOW 90 days.
        configureSlas(
                new VulnerabilitySla("CRITICAL", 7, 3),
                new VulnerabilitySla("HIGH", 30, 15),
                new VulnerabilitySla("MEDIUM", 90, 45),
                new VulnerabilitySla("LOW", 180, 90));

        liveAssessmentId = assessment(null).getId();
    }

    // ── Warning-window boundary (the crux of the math) ─────────────────────────

    @Test
    void warningThreshold_isInclusiveOnTheBoundary() {
        // HIGH threshold = 30 - 15 = 15 days. Opened exactly 15 days ago must count.
        vuln(VulnerabilitySeverity.HIGH, 15, "Open", null);
        assertThat(service.queueCount()).isEqualTo(1);
    }

    @Test
    void warningThreshold_excludesOneDayBefore() {
        // Opened 14 days ago is one day short of HIGH's 15-day threshold → excluded.
        vuln(VulnerabilitySeverity.HIGH, 14, "Open", null);
        assertThat(service.queueCount()).isZero();
    }

    @Test
    void countsWarningWindowAndPastDue_excludesFresh() {
        vuln(VulnerabilitySeverity.HIGH, 20, "Open", null);   // in warning window (>= 15)
        vuln(VulnerabilitySeverity.HIGH, 400, "Open", null);  // long past due
        vuln(VulnerabilitySeverity.HIGH, 5, "Open", null);    // fresh — excluded
        assertThat(service.queueCount()).isEqualTo(2);
    }

    // ── Per-severity threshold independence ────────────────────────────────────

    @Test
    void eachSeverityUsesItsOwnThreshold_atEightDays() {
        // Opened 8 days ago: only CRITICAL (threshold 4) is due; HIGH/MEDIUM/LOW are not.
        vuln(VulnerabilitySeverity.CRITICAL, 8, "Open", null);      // 8 >= 4  → counts
        vuln(VulnerabilitySeverity.HIGH, 8, "Open", null);          // 8 <  15 → no
        vuln(VulnerabilitySeverity.MEDIUM, 8, "Open", null);        // 8 <  45 → no
        vuln(VulnerabilitySeverity.LOW, 8, "Open", null);           // 8 <  90 → no
        assertThat(service.queueCount()).isEqualTo(1);
    }

    @Test
    void everySeverityCountsOncePastItsOwnThreshold() {
        // Each opened one day past its threshold (CRITICAL 4, HIGH 15, MEDIUM 45, LOW 90).
        vuln(VulnerabilitySeverity.CRITICAL, 5, "Open", null);
        vuln(VulnerabilitySeverity.HIGH, 16, "Open", null);
        vuln(VulnerabilitySeverity.MEDIUM, 46, "Open", null);
        vuln(VulnerabilitySeverity.LOW, 91, "Open", null);
        assertThat(service.queueCount()).isEqualTo(4);
    }

    @Test
    void eachSeverityExcludedOneDayBeforeItsThreshold() {
        vuln(VulnerabilitySeverity.CRITICAL, 3, "Open", null);   // < 4
        vuln(VulnerabilitySeverity.HIGH, 14, "Open", null);      // < 15
        vuln(VulnerabilitySeverity.MEDIUM, 44, "Open", null);    // < 45
        vuln(VulnerabilitySeverity.LOW, 89, "Open", null);       // < 90
        assertThat(service.queueCount()).isZero();
    }

    // ── warningDays = 0 edge (warning window collapses onto the past-due date) ──

    @Test
    void warningDaysZero_thresholdEqualsPastDueDays() {
        configureSlas(new VulnerabilitySla("HIGH", 10, 0)); // threshold = 10
        vuln(VulnerabilitySeverity.HIGH, 10, "Open", null); // exactly at → counts
        vuln(VulnerabilitySeverity.HIGH, 9, "Open", null);  // one short → excluded
        assertThat(service.queueCount()).isEqualTo(1);
    }

    @Test
    void severityMatch_isCaseInsensitive() {
        // Config may store a severity as "high"/"High"; it must still match the enum
        // rather than being silently dropped from tracking.
        configureSlas(new VulnerabilitySla("high", 30, 15));
        vuln(VulnerabilitySeverity.HIGH, 40, "Open", null); // past due

        assertThat(service.queueCount()).isEqualTo(1);
    }

    // ── Status / severity / lifecycle exclusions ───────────────────────────────

    @Test
    void excludesClosedAndExceptionStatus() {
        vuln(VulnerabilitySeverity.HIGH, 40, "Closed", null);
        vuln(VulnerabilitySeverity.HIGH, 40, "Exception", null);
        assertThat(service.queueCount()).isZero();
    }

    @Test
    void nullStatusCountsAsOpen() {
        // status column is nullable; null must be treated as open (IS DISTINCT FROM), not dropped.
        vuln(VulnerabilitySeverity.HIGH, 40, null, null);
        assertThat(service.queueCount()).isEqualTo(1);
    }

    @Test
    void excludesVulnsWithClosedAtSet() {
        // Remediated (closed_at set) → excluded even though old and status still looks open.
        vuln(VulnerabilitySeverity.HIGH, 40, "Open", LocalDateTime.now().minusDays(1));
        assertThat(service.queueCount()).isZero();
    }

    @Test
    void excludesUntrackedSeverity() {
        // No SLA for INFORMATIONAL, so even a very old one is never tracked.
        vuln(VulnerabilitySeverity.INFORMATIONAL, 400, "Open", null);
        assertThat(service.queueCount()).isZero();
    }

    @Test
    void excludesVulnsWithNoOpenedAt() {
        vulnerabilityRepository.save(Vulnerability.builder()
                .name("v-" + System.nanoTime())
                .severity(VulnerabilitySeverity.HIGH)
                .assessmentId(liveAssessmentId)
                .order(0).status("Open")
                .openedAt(null)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
        assertThat(service.queueCount()).isZero();
    }

    @Test
    void excludesVulnsOfDeletedAssessments() {
        var gone = assessment(LocalDateTime.now()).getId();
        vuln(VulnerabilitySeverity.HIGH, 40, "Open", null);                 // live → counts
        vuln(gone, VulnerabilitySeverity.HIGH, 40, "Open", null);           // deleted assessment → excluded
        assertThat(service.queueCount()).isEqualTo(1);
    }

    @Test
    void excludesSoftDeletedVulns() {
        vuln(VulnerabilitySeverity.HIGH, 40, "Open", null); // counts
        vulnerabilityRepository.save(Vulnerability.builder()
                .name("v-" + System.nanoTime())
                .severity(VulnerabilitySeverity.HIGH)
                .assessmentId(liveAssessmentId)
                .order(0).status("Open")
                .openedAt(LocalDateTime.now().minusDays(40))
                .deletedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
        assertThat(service.queueCount()).isEqualTo(1);
    }

    // ── Retest half ────────────────────────────────────────────────────────────

    @Test
    void countsOnlyOpenRetests() {
        retest("REQUESTED");
        retest("SCHEDULED");
        retest("IN_PROGRESS");
        retest("PASSED");
        retest("FAILED");
        retest("CANCELLED");
        assertThat(service.queueCount()).isEqualTo(3);
    }

    @Test
    void excludesSoftDeletedRetests() {
        retest("SCHEDULED");
        retestRepository.save(Retest.builder()
                .assessmentId("assessment-1").applicationId("app-1").vulnerabilityId("vuln-1")
                .status("SCHEDULED").createdBy("system").lastUpdatedBy("system")
                .deletedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
        assertThat(service.queueCount()).isEqualTo(1);
    }

    // ── Summation across both halves ───────────────────────────────────────────

    @Test
    void noSlasConfigured_stillCountsRetests() {
        configureSlas(); // empty
        vuln(VulnerabilitySeverity.HIGH, 400, "Open", null); // would count if SLAs existed
        retest("SCHEDULED");
        assertThat(service.queueCount()).isEqualTo(1); // only the retest
    }

    @Test
    void grandTotal_sumsEverySeverityPlusRetests() {
        // 3 due vulns across severities (each past its threshold) + 2 open retests = 5.
        vuln(VulnerabilitySeverity.CRITICAL, 10, "Open", null);
        vuln(VulnerabilitySeverity.HIGH, 40, "Open", null);
        vuln(VulnerabilitySeverity.LOW, 200, "Open", null);
        vuln(VulnerabilitySeverity.MEDIUM, 5, "Open", null);   // fresh MEDIUM (< 45) — excluded
        retest("REQUESTED");
        retest("IN_PROGRESS");
        assertThat(service.queueCount()).isEqualTo(5);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void configureSlas(VulnerabilitySla... slas) {
        workflowConfigRepository.save(AssessmentWorkflowConfig.builder()
                .id("singleton")
                .vulnerabilitySlas(List.of(slas))
                .build());
    }

    private Assessment assessment(LocalDateTime deletedAt) {
        return assessmentRepository.save(Assessment.builder()
                .name("A-" + System.nanoTime())
                .applicationId("app-1")
                .assessmentTypeId("type-1")
                .organizationId("org-1")
                .status("IN_PROGRESS")
                .deletedAt(deletedAt)
                .createdAt(LocalDateTime.now())
                .build());
    }

    /** Seed a vuln on the default live assessment. */
    private void vuln(VulnerabilitySeverity sev, int openedDaysAgo, String status, LocalDateTime closedAt) {
        vuln(liveAssessmentId, sev, openedDaysAgo, status, closedAt);
    }

    private void vuln(String assessmentId, VulnerabilitySeverity sev, int openedDaysAgo,
                      String status, LocalDateTime closedAt) {
        vulnerabilityRepository.save(Vulnerability.builder()
                .name("v-" + System.nanoTime())
                .severity(sev)
                .assessmentId(assessmentId)
                .order(0)
                .status(status)
                .openedAt(LocalDateTime.now().minusDays(openedDaysAgo))
                .closedAt(closedAt)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private void retest(String status) {
        retestRepository.save(Retest.builder()
                .assessmentId("assessment-1").applicationId("app-1").vulnerabilityId("vuln-1")
                .status(status).createdBy("system").lastUpdatedBy("system")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
    }
}
