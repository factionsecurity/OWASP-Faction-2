package com.faction.clientportal.scheduled;

import com.faction.clientportal.dto.AssessmentDto;
import com.faction.clientportal.dto.CreateAssessmentRequest;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentFrequency;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
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
 * <p>The successor is created with the same application, assessment type, report template,
 * assessors, and managers. Its start date is set to completedDate + interval and its
 * status is set to the configured newAssessmentStatus.</p>
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

    @Scheduled(cron = "0 0 1 * * ?") // 1:00 AM every day
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
                        .reportTemplateId(assessment.getReportTemplateId())
                        .assessorIds(assessment.getAssessorIds())
                        .engagementManagerId(assessment.getEngagementManagerId())
                        .remediationManagerId(assessment.getRemediationManagerId())
                        .startDate(newStartDate)
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
