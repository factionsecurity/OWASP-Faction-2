package com.faction.clientportal.scheduled;

import com.faction.clientportal.dto.AssessmentDto;
import com.faction.clientportal.dto.CreateAssessmentRequest;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentFrequency;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.ReportTemplateRepository;
import com.faction.clientportal.service.AssessmentService;
import com.faction.clientportal.service.NotebookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Daily job that auto-schedules a successor assessment for any completed assessment
 * whose application has a YEARLY or CUSTOM assessment frequency, once the configured
 * interval has elapsed since the assessment was completed.
 *
 * <ul>
 *   <li>Yearly: successor scheduled 330 days after completion</li>
 *   <li>Custom: successor scheduled after the configured number of months</li>
 * </ul>
 *
 * <p>The successor is created with the same application, assessment type and report template
 * (or the type's default template if that one is gone), with nobody assigned and no dates, in
 * the configured newAssessmentStatus. It appears once completedDate + interval has passed.</p>
 *
 * <p>The {@code autoScheduledSuccessorId} field on the original assessment is set after
 * creation to prevent duplicate scheduling across runs.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssessmentSchedulerJob {

    static final int YEARLY_SCHEDULE_DAYS = 330;

    private final AssessmentRepository assessmentRepository;
    private final ApplicationRepository applicationRepository;
    private final AssessmentService assessmentService;
    private final NotebookService notebookService;
    private final ReportTemplateRepository reportTemplateRepository;

    @Scheduled(cron = "${app.scheduling.assessment-scheduler-cron:0 0 1 * * ?}") // 1:00 AM daily by default
    public void scheduleSuccessorAssessments() {
        List<Assessment> candidates = assessmentRepository.findCompletedWithNoSuccessor();

        if (candidates.isEmpty()) {
            log.info("Assessment scheduler: no candidates for auto-scheduling");
            return;
        }

        log.info("Assessment scheduler: checking {} completed assessment(s) for successor scheduling", candidates.size());

        LocalDateTime now = LocalDateTime.now();
        int scheduled = 0;

        for (Assessment assessment : candidates) {
            try {
                Application app = applicationRepository.findById(assessment.getApplicationId()).orElse(null);
                if (app == null) continue;

                LocalDateTime newStartDate = resolveStartDate(app, assessment.getCompletedDate());
                if (newStartDate == null || newStartDate.isAfter(now)) {
                    continue; // not yet due, or frequency is not auto-schedulable
                }

                CreateAssessmentRequest request = CreateAssessmentRequest.builder()
                        .name(assessment.getName())
                        .applicationId(assessment.getApplicationId())
                        .assessmentTypeId(assessment.getAssessmentTypeId())
                        .reportTemplateId(usableTemplateId(assessment))
                        // Deliberately no assessors, managers or dates: the successor is a
                        // placeholder for work that is now due, and copying people would notify
                        // them of an engagement nobody has planned yet. The due date above only
                        // decides *when* the placeholder appears; scheduling it is a person's job.
                        .build();

                AssessmentDto successor = assessmentService.createAssessment(request, "system");

                assessment.setAutoScheduledSuccessorId(successor.getId());
                assessmentRepository.save(assessment);

                log.info("Assessment scheduler: created successor {} for application {} (predecessor: {})",
                        successor.getId(), assessment.getApplicationId(), assessment.getId());
                scheduled++;

            } catch (Exception e) {
                log.error("Assessment scheduler: failed to create successor for assessment {}: {}",
                        assessment.getId(), e.getMessage(), e);
            }
        }

        log.info("Assessment scheduler: auto-scheduled {} successor assessment(s)", scheduled);
    }

    /**
     * The predecessor's template, unless it has since been deleted or deactivated — creation
     * would refuse it, and the assessment would stay stuck on every run. A blank id lets creation
     * fall back to the type's default template instead.
     */
    private String usableTemplateId(Assessment predecessor) {
        String id = predecessor.getReportTemplateId();
        if (id == null) return null;
        boolean usable = reportTemplateRepository.findByIdAndDeletedAtIsNull(id)
                .map(t -> Boolean.TRUE.equals(t.getActive()))
                .orElse(false);
        if (!usable) {
            log.info("Assessment scheduler: template {} of assessment {} is no longer usable; "
                    + "the successor gets the default template for its type", id, predecessor.getId());
            return null;
        }
        return id;
    }

    /**
     * Returns the scheduled start date for the successor based on the application's
     * frequency setting, or {@code null} if the frequency is not auto-schedulable.
     */
    LocalDateTime resolveStartDate(Application app, LocalDateTime completedDate) {
        String freq = app.getAssessmentFrequency();
        if (AssessmentFrequency.YEARLY.getDisplayName().equals(freq)) {
            return completedDate.plusDays(YEARLY_SCHEDULE_DAYS);
        }
        if (AssessmentFrequency.CUSTOM.getDisplayName().equals(freq)
                && app.getCustomFrequencyMonths() != null
                && app.getCustomFrequencyMonths() > 0) {
            return completedDate.plusMonths(app.getCustomFrequencyMonths());
        }
        return null;
    }
}
