package com.faction.clientportal.scheduled;

import com.faction.clientportal.dto.AssessmentDto;
import com.faction.clientportal.dto.CreateAssessmentRequest;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentFrequency;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.service.AssessmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssessmentSchedulerJobTest {

    @Mock
    private AssessmentRepository assessmentRepository;

    @Mock
    private ApplicationRepository applicationRepository;

    @Mock
    private AssessmentService assessmentService;

    @InjectMocks
    private AssessmentSchedulerJob job;

    private Assessment completedAssessment;
    private Application yearlyApp;

    @BeforeEach
    void setUp() {
        completedAssessment = Assessment.builder()
                .id("assessment-1")
                .name("Annual Pentest")
                .applicationId("app-1")
                .assessmentTypeId("type-1")
                .reportTemplateId("template-1")
                .engagementManagerId("manager-1")
                .remediationManagerId("rem-manager-1")
                .assessorIds(List.of("assessor-1"))
                .completedDate(LocalDateTime.now().minusDays(331))
                .build();

        yearlyApp = Application.builder()
                .id("app-1")
                .name("Critical App")
                .assessmentFrequency(AssessmentFrequency.YEARLY.getDisplayName())
                .build();
    }

    // ── resolveStartDate unit tests ─────────────────────────────────────────

    @Test
    void resolveStartDate_yearly_returns330DaysLater() {
        LocalDateTime completed = LocalDateTime.of(2025, 1, 1, 0, 0);
        LocalDateTime result = job.resolveStartDate(yearlyApp, completed);
        assertThat(result).isEqualTo(completed.plusDays(AssessmentSchedulerJob.YEARLY_SCHEDULE_DAYS));
    }

    @Test
    void resolveStartDate_custom_returnsMonthsLater() {
        Application customApp = Application.builder()
                .assessmentFrequency(AssessmentFrequency.CUSTOM.getDisplayName())
                .customFrequencyMonths(6)
                .build();
        LocalDateTime completed = LocalDateTime.of(2025, 1, 1, 0, 0);
        LocalDateTime result = job.resolveStartDate(customApp, completed);
        assertThat(result).isEqualTo(completed.plusMonths(6));
    }

    @Test
    void resolveStartDate_customWithNullMonths_returnsNull() {
        Application customApp = Application.builder()
                .assessmentFrequency(AssessmentFrequency.CUSTOM.getDisplayName())
                .customFrequencyMonths(null)
                .build();
        assertThat(job.resolveStartDate(customApp, LocalDateTime.now())).isNull();
    }

    @Test
    void resolveStartDate_adHoc_returnsNull() {
        Application adHocApp = Application.builder()
                .assessmentFrequency("Ad Hoc")
                .build();
        assertThat(job.resolveStartDate(adHocApp, LocalDateTime.now())).isNull();
    }

    // ── scheduler integration tests ─────────────────────────────────────────

    @Test
    void schedulesSuccessorForYearlyApp() throws Exception {
        AssessmentDto successor = AssessmentDto.builder().id("successor-1").build();

        when(assessmentRepository.findCompletedWithNoSuccessor()).thenReturn(List.of(completedAssessment));
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(yearlyApp));
        when(assessmentService.createAssessment(any(), eq("system"))).thenReturn(successor);

        job.scheduleSuccessorAssessments();

        ArgumentCaptor<CreateAssessmentRequest> reqCaptor = ArgumentCaptor.forClass(CreateAssessmentRequest.class);
        verify(assessmentService).createAssessment(reqCaptor.capture(), eq("system"));

        CreateAssessmentRequest req = reqCaptor.getValue();
        assertThat(req.getName()).isEqualTo("Annual Pentest");
        assertThat(req.getApplicationId()).isEqualTo("app-1");
        assertThat(req.getStartDate()).isEqualTo(
                completedAssessment.getCompletedDate().plusDays(AssessmentSchedulerJob.YEARLY_SCHEDULE_DAYS));

        verify(assessmentRepository).save(completedAssessment);
        assertThat(completedAssessment.getAutoScheduledSuccessorId()).isEqualTo("successor-1");
    }

    @Test
    void schedulesSuccessorForCustomApp() throws Exception {
        Application customApp = Application.builder()
                .id("app-1")
                .assessmentFrequency(AssessmentFrequency.CUSTOM.getDisplayName())
                .customFrequencyMonths(3)
                .build();
        completedAssessment.setCompletedDate(LocalDateTime.now().minusMonths(4));
        AssessmentDto successor = AssessmentDto.builder().id("successor-custom").build();

        when(assessmentRepository.findCompletedWithNoSuccessor()).thenReturn(List.of(completedAssessment));
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(customApp));
        when(assessmentService.createAssessment(any(), eq("system"))).thenReturn(successor);

        job.scheduleSuccessorAssessments();

        ArgumentCaptor<CreateAssessmentRequest> reqCaptor = ArgumentCaptor.forClass(CreateAssessmentRequest.class);
        verify(assessmentService).createAssessment(reqCaptor.capture(), eq("system"));
        assertThat(reqCaptor.getValue().getStartDate())
                .isEqualTo(completedAssessment.getCompletedDate().plusMonths(3));
        assertThat(completedAssessment.getAutoScheduledSuccessorId()).isEqualTo("successor-custom");
    }

    @Test
    void skipsWhenNotYetDue() throws Exception {
        // completed only 100 days ago — not yet due for yearly
        completedAssessment.setCompletedDate(LocalDateTime.now().minusDays(100));

        when(assessmentRepository.findCompletedWithNoSuccessor()).thenReturn(List.of(completedAssessment));
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(yearlyApp));

        job.scheduleSuccessorAssessments();

        verify(assessmentService, never()).createAssessment(any(), any());
    }

    @Test
    void skipsAdHocApp() throws Exception {
        yearlyApp.setAssessmentFrequency("Ad Hoc");

        when(assessmentRepository.findCompletedWithNoSuccessor()).thenReturn(List.of(completedAssessment));
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(yearlyApp));

        job.scheduleSuccessorAssessments();

        verify(assessmentService, never()).createAssessment(any(), any());
    }

    @Test
    void skipsWhenApplicationNotFound() throws Exception {
        when(assessmentRepository.findCompletedWithNoSuccessor()).thenReturn(List.of(completedAssessment));
        when(applicationRepository.findById("app-1")).thenReturn(Optional.empty());

        job.scheduleSuccessorAssessments();

        verify(assessmentService, never()).createAssessment(any(), any());
    }

    @Test
    void doesNothingWhenNoCandidates() throws Exception {
        when(assessmentRepository.findCompletedWithNoSuccessor()).thenReturn(List.of());

        job.scheduleSuccessorAssessments();

        verify(applicationRepository, never()).findById(any());
        verify(assessmentService, never()).createAssessment(any(), any());
    }

    @Test
    void continuesAfterSingleFailure() throws Exception {
        Assessment second = Assessment.builder()
                .id("assessment-2")
                .name("Annual Pentest 2")
                .applicationId("app-1")
                .assessmentTypeId("type-1")
                .reportTemplateId("template-1")
                .assessorIds(List.of())
                .completedDate(LocalDateTime.now().minusDays(331))
                .build();

        AssessmentDto successor = AssessmentDto.builder().id("successor-2").build();

        when(assessmentRepository.findCompletedWithNoSuccessor())
                .thenReturn(List.of(completedAssessment, second));
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(yearlyApp));
        when(assessmentService.createAssessment(any(), eq("system")))
                .thenThrow(new RuntimeException("Template not found"))
                .thenReturn(successor);

        job.scheduleSuccessorAssessments();

        verify(assessmentService, times(2)).createAssessment(any(), eq("system"));
        verify(assessmentRepository, times(1)).save(second);
        assertThat(completedAssessment.getAutoScheduledSuccessorId()).isNull();
        assertThat(second.getAutoScheduledSuccessorId()).isEqualTo("successor-2");
    }
}
