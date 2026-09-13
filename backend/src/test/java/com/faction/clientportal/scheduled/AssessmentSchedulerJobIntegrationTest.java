package com.faction.clientportal.scheduled;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.*;
import com.faction.clientportal.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link AssessmentSchedulerJob} using a real MongoDB instance
 * (via Testcontainers). Tests cover the full scheduling lifecycle: assessment completion,
 * successor creation, idempotency, and frequency-based filtering.
 */
@SpringBootTest
@ActiveProfiles("test")
class AssessmentSchedulerJobIntegrationTest extends TestContainersConfig {

    @Autowired
    private AssessmentSchedulerJob job;

    @Autowired
    private Environment environment;

    @Autowired
    private AssessmentRepository assessmentRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private AssessmentTypeRepository assessmentTypeRepository;

    @Autowired
    private ReportTemplateRepository reportTemplateRepository;

    @Autowired
    private AssessmentWorkflowConfigRepository workflowConfigRepository;

    private Application yearlyApp;
    private AssessmentType assessmentType;
    private ReportTemplate template;

    @BeforeEach
    void setUp() {
        assessmentRepository.deleteAll();
        applicationRepository.deleteAll();
        assessmentTypeRepository.deleteAll();
        reportTemplateRepository.deleteAll();
        workflowConfigRepository.deleteAll();

        assessmentType = assessmentTypeRepository.save(
                AssessmentType.builder()
                        .name("Pentest")
                        .description("Security assessment")
                        .createdAt(LocalDateTime.now())
                        .build());

        template = reportTemplateRepository.save(
                ReportTemplate.builder()
                        .name("Default Template")
                        .assessmentTypeId(assessmentType.getId())
                        .version(1)
                        .active(true)
                        .userDefinedFields(new ArrayList<>())
                        .createdAt(LocalDateTime.now())
                        .build());

        yearlyApp = applicationRepository.save(
                Application.builder()
                        .name("Critical App")
                        .assessmentFrequency(AssessmentFrequency.YEARLY.getDisplayName())
                        .createdAt(LocalDateTime.now())
                        .build());
    }

    // ── Yearly scheduling ───────────────────────────────────────────────────

    @Test
    void yearly_createsSuccessorWhen330DaysHavePassed() {
        LocalDateTime completedAt = LocalDateTime.now().minusDays(331);
        Assessment original = saveCompletedAssessment("Annual Pentest", yearlyApp, completedAt);

        job.scheduleSuccessorAssessments();

        // Original should now have a successor ID set
        Assessment updated = assessmentRepository.findById(original.getId()).orElseThrow();
        assertThat(updated.getAutoScheduledSuccessorId()).isNotNull();

        // Successor should exist with correct start date and new status
        Assessment successor = assessmentRepository.findById(updated.getAutoScheduledSuccessorId()).orElseThrow();
        assertThat(successor.getName()).isEqualTo("Annual Pentest");
        assertThat(successor.getApplicationId()).isEqualTo(yearlyApp.getId());
        assertThat(successor.getStartDate()).isNull();
        assertThat(successor.getPlannedEndDate()).isNull();
        assertThat(successor.getAutoScheduledSuccessorId()).isNull();
    }

    @Test
    void yearly_doesNotCreateSuccessorBefore330Days() {
        Assessment original = saveCompletedAssessment("Annual Pentest", yearlyApp,
                LocalDateTime.now().minusDays(100));

        job.scheduleSuccessorAssessments();

        Assessment updated = assessmentRepository.findById(original.getId()).orElseThrow();
        assertThat(updated.getAutoScheduledSuccessorId()).isNull();
        assertThat(assessmentRepository.count()).isEqualTo(1); // no successor created
    }

    @Test
    void yearly_exactlyAt330Days_createsSuccessor() {
        // Completed exactly 330 days ago — threshold is now.minusDays(330), so just past it
        LocalDateTime completedAt = LocalDateTime.now().minusDays(330).minusMinutes(1);
        Assessment original = saveCompletedAssessment("Annual Pentest", yearlyApp, completedAt);

        job.scheduleSuccessorAssessments();

        Assessment updated = assessmentRepository.findById(original.getId()).orElseThrow();
        assertThat(updated.getAutoScheduledSuccessorId()).isNotNull();
    }

    // ── Custom scheduling ───────────────────────────────────────────────────

    @Test
    void custom_createsSuccessorAfterConfiguredMonths() {
        Application customApp = applicationRepository.save(
                Application.builder()
                        .name("Custom App")
                        .assessmentFrequency(AssessmentFrequency.CUSTOM.getDisplayName())
                        .customFrequencyMonths(6)
                        .createdAt(LocalDateTime.now())
                        .build());

        LocalDateTime completedAt = LocalDateTime.now().minusMonths(7);
        Assessment original = saveCompletedAssessment("Semi-Annual Pentest", customApp, completedAt);

        job.scheduleSuccessorAssessments();

        Assessment updated = assessmentRepository.findById(original.getId()).orElseThrow();
        assertThat(updated.getAutoScheduledSuccessorId()).isNotNull();

        Assessment successor = assessmentRepository.findById(updated.getAutoScheduledSuccessorId()).orElseThrow();
        assertThat(successor.getStartDate()).isNull();
    }

    @Test
    void custom_doesNotCreateSuccessorBeforeConfiguredMonths() {
        Application customApp = applicationRepository.save(
                Application.builder()
                        .name("Custom App")
                        .assessmentFrequency(AssessmentFrequency.CUSTOM.getDisplayName())
                        .customFrequencyMonths(6)
                        .createdAt(LocalDateTime.now())
                        .build());

        Assessment original = saveCompletedAssessment("Semi-Annual Pentest", customApp,
                LocalDateTime.now().minusMonths(3));

        job.scheduleSuccessorAssessments();

        Assessment updated = assessmentRepository.findById(original.getId()).orElseThrow();
        assertThat(updated.getAutoScheduledSuccessorId()).isNull();
    }

    @Test
    void custom_withNullMonths_doesNotSchedule() {
        Application customApp = applicationRepository.save(
                Application.builder()
                        .name("Custom App No Months")
                        .assessmentFrequency(AssessmentFrequency.CUSTOM.getDisplayName())
                        .customFrequencyMonths(null)
                        .createdAt(LocalDateTime.now())
                        .build());

        Assessment original = saveCompletedAssessment("Pentest", customApp,
                LocalDateTime.now().minusMonths(13));

        job.scheduleSuccessorAssessments();

        Assessment updated = assessmentRepository.findById(original.getId()).orElseThrow();
        assertThat(updated.getAutoScheduledSuccessorId()).isNull();
    }

    // ── Frequency filtering ─────────────────────────────────────────────────

    @Test
    void adHoc_neverSchedulesSuccessor() {
        Application adHocApp = applicationRepository.save(
                Application.builder()
                        .name("Ad Hoc App")
                        .assessmentFrequency("Ad Hoc")
                        .createdAt(LocalDateTime.now())
                        .build());

        Assessment original = saveCompletedAssessment("Ad Hoc Pentest", adHocApp,
                LocalDateTime.now().minusDays(400));

        job.scheduleSuccessorAssessments();

        Assessment updated = assessmentRepository.findById(original.getId()).orElseThrow();
        assertThat(updated.getAutoScheduledSuccessorId()).isNull();
        assertThat(assessmentRepository.count()).isEqualTo(1);
    }

    @Test
    void quarterly_neverSchedulesSuccessor() {
        Application quarterlyApp = applicationRepository.save(
                Application.builder()
                        .name("Quarterly App")
                        .assessmentFrequency(AssessmentFrequency.QUARTERLY.getDisplayName())
                        .createdAt(LocalDateTime.now())
                        .build());

        Assessment original = saveCompletedAssessment("Quarterly Pentest", quarterlyApp,
                LocalDateTime.now().minusDays(400));

        job.scheduleSuccessorAssessments();

        Assessment updated = assessmentRepository.findById(original.getId()).orElseThrow();
        assertThat(updated.getAutoScheduledSuccessorId()).isNull();
    }

    // ── Idempotency ─────────────────────────────────────────────────────────

    @Test
    void idempotent_doesNotCreateDuplicateSuccessor() {
        Assessment original = saveCompletedAssessment("Annual Pentest", yearlyApp,
                LocalDateTime.now().minusDays(400));

        job.scheduleSuccessorAssessments();
        job.scheduleSuccessorAssessments(); // run twice

        long total = assessmentRepository.count();
        assertThat(total).isEqualTo(2); // only original + one successor

        Assessment updated = assessmentRepository.findById(original.getId()).orElseThrow();
        String successorId = updated.getAutoScheduledSuccessorId();
        assertThat(successorId).isNotNull();

        // Second run should not have changed the successor ID
        assertThat(assessmentRepository.findById(original.getId()).orElseThrow()
                .getAutoScheduledSuccessorId()).isEqualTo(successorId);
    }

    // ── Successor content ───────────────────────────────────────────────────

    /**
     * A successor is a placeholder for work a year out: whoever ran the last one may have moved
     * on, so nobody is assigned until someone schedules it properly. Copying the people would
     * also fire assignment notifications for an engagement nobody has planned yet.
     */
    @Test
    void successor_startsUnassigned() {
        Assessment original = assessmentRepository.save(
                Assessment.builder()
                        .name("Annual Pentest")
                        .applicationId(yearlyApp.getId())
                        .assessmentTypeId(assessmentType.getId())
                        .reportTemplateId(template.getId())
                        .assessorIds(List.of("assessor-1", "assessor-2"))
                        .engagementManagerId("manager-1")
                        .remediationManagerId("rem-1")
                        .status("Completed")
                        .completedDate(LocalDateTime.now().minusDays(331))
                        .fieldDefinitions(new ArrayList<>())
                        .fieldValues(new HashMap<>())
                        .createdAt(LocalDateTime.now())
                        .build());

        job.scheduleSuccessorAssessments();

        Assessment updated = assessmentRepository.findById(original.getId()).orElseThrow();
        Assessment successor = assessmentRepository.findById(updated.getAutoScheduledSuccessorId()).orElseThrow();

        assertThat(successor.getAssessorIds()).isEmpty();
        assertThat(successor.getEngagementManagerId()).isNull();
        assertThat(successor.getRemediationManagerId()).isNull();
        assertThat(successor.getApplicationId()).isEqualTo(yearlyApp.getId());
        assertThat(successor.getAssessmentTypeId()).isEqualTo(assessmentType.getId());
    }

    @Test
    void successor_receivesNewAssessmentStatus() {
        // Configure workflow with a custom new-status
        workflowConfigRepository.save(
                AssessmentWorkflowConfig.builder()
                        .id("singleton")
                        .statuses(List.of("Pending", "Active", "Done"))
                        .newAssessmentStatus("Pending")
                        .inProgressStatus("Active")
                        .completedStatus("Done")
                        .build());

        Assessment original = saveCompletedAssessment("Annual Pentest", yearlyApp,
                LocalDateTime.now().minusDays(331));

        job.scheduleSuccessorAssessments();

        Assessment updated = assessmentRepository.findById(original.getId()).orElseThrow();
        Assessment successor = assessmentRepository.findById(updated.getAutoScheduledSuccessorId()).orElseThrow();
        assertThat(successor.getStatus()).isEqualTo("Pending");
    }

    // ── Multiple assessments ─────────────────────────────────────────────────

    @Test
    void processesMultipleEligibleAssessmentsInOnRun() {
        Assessment a1 = saveCompletedAssessment("Pentest A", yearlyApp, LocalDateTime.now().minusDays(400));
        Assessment a2 = saveCompletedAssessment("Pentest B", yearlyApp, LocalDateTime.now().minusDays(500));

        job.scheduleSuccessorAssessments();

        assertThat(assessmentRepository.findById(a1.getId()).orElseThrow()
                .getAutoScheduledSuccessorId()).isNotNull();
        assertThat(assessmentRepository.findById(a2.getId()).orElseThrow()
                .getAutoScheduledSuccessorId()).isNotNull();
        assertThat(assessmentRepository.count()).isEqualTo(4); // 2 originals + 2 successors
    }

    @Test
    void mixedFrequencies_onlyEligibleAppIsScheduled() {
        Application adHocApp = applicationRepository.save(
                Application.builder()
                        .name("Ad Hoc App")
                        .assessmentFrequency("Ad Hoc")
                        .createdAt(LocalDateTime.now())
                        .build());

        Assessment yearlyAssessment = saveCompletedAssessment("Yearly Pentest", yearlyApp,
                LocalDateTime.now().minusDays(400));
        Assessment adHocAssessment = saveCompletedAssessment("Ad Hoc Pentest", adHocApp,
                LocalDateTime.now().minusDays(400));

        job.scheduleSuccessorAssessments();

        assertThat(assessmentRepository.findById(yearlyAssessment.getId()).orElseThrow()
                .getAutoScheduledSuccessorId()).isNotNull();
        assertThat(assessmentRepository.findById(adHocAssessment.getId()).orElseThrow()
                .getAutoScheduledSuccessorId()).isNull();
        assertThat(assessmentRepository.count()).isEqualTo(3); // 2 originals + 1 successor
    }

    // ── Repository query ─────────────────────────────────────────────────────

    @Test
    void assessmentWithExistingSuccessorId_isExcludedFromCandidates() {
        Assessment already = assessmentRepository.save(
                Assessment.builder()
                        .name("Already Scheduled")
                        .applicationId(yearlyApp.getId())
                        .assessmentTypeId(assessmentType.getId())
                        .reportTemplateId(template.getId())
                        .status("Completed")
                        .completedDate(LocalDateTime.now().minusDays(400))
                        .autoScheduledSuccessorId("some-existing-successor-id")
                        .fieldDefinitions(new ArrayList<>())
                        .fieldValues(new HashMap<>())
                        .createdAt(LocalDateTime.now())
                        .build());

        job.scheduleSuccessorAssessments();

        // Should not have changed
        assertThat(assessmentRepository.findById(already.getId()).orElseThrow()
                .getAutoScheduledSuccessorId()).isEqualTo("some-existing-successor-id");
        assertThat(assessmentRepository.count()).isEqualTo(1);
    }

    @Test
    void assessmentWithNullCompletedDate_isExcludedFromCandidates() {
        assessmentRepository.save(
                Assessment.builder()
                        .name("In Progress")
                        .applicationId(yearlyApp.getId())
                        .assessmentTypeId(assessmentType.getId())
                        .reportTemplateId(template.getId())
                        .status("In Progress")
                        .completedDate(null)
                        .fieldDefinitions(new ArrayList<>())
                        .fieldValues(new HashMap<>())
                        .createdAt(LocalDateTime.now())
                        .build());

        job.scheduleSuccessorAssessments();

        assertThat(assessmentRepository.count()).isEqualTo(1); // no successor created
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Assessment saveCompletedAssessment(String name, Application app, LocalDateTime completedAt) {
        return assessmentRepository.save(
                Assessment.builder()
                        .name(name)
                        .applicationId(app.getId())
                        .assessmentTypeId(assessmentType.getId())
                        .reportTemplateId(template.getId())
                        .assessorIds(new ArrayList<>())
                        .status("Completed")
                        .completedDate(completedAt)
                        .fieldDefinitions(new ArrayList<>())
                        .fieldValues(new HashMap<>())
                        .createdAt(LocalDateTime.now())
                        .build());
    }

    /**
     * The run time is configuration, not code: a developer testing rescheduling sets
     * ASSESSMENT_SCHEDULER_CRON to fire every minute instead of waiting for 01:00, exactly as the
     * SLA digest already allows. The annotation must read the property, and the shipped default
     * must be a cron Spring can parse.
     */
    @Test
    void runTimeComesFromTheAssessmentSchedulerCronProperty() throws Exception {
        String cron = AssessmentSchedulerJob.class
                .getMethod("scheduleSuccessorAssessments")
                .getAnnotation(Scheduled.class)
                .cron();

        assertThat(cron).contains("${app.scheduling.assessment-scheduler-cron");
        String configured = environment.getProperty("app.scheduling.assessment-scheduler-cron");
        assertThat(configured).isNotBlank();
        assertThat(environment.resolvePlaceholders(cron)).isEqualTo(configured);
        assertThat(CronExpression.isValidExpression(configured)).isTrue();
    }
}
