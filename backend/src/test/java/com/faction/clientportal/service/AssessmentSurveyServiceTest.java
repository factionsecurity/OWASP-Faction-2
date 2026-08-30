package com.faction.clientportal.service;

import com.faction.clientportal.dto.AddAssessmentSurveyRequest;
import com.faction.clientportal.dto.AssessmentSurveyDto;
import com.faction.clientportal.dto.UpdateAssessmentSurveyRequest;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentSurvey;
import com.faction.clientportal.model.SurveyStatus;
import com.faction.clientportal.model.SurveyTemplate;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentSurveyRepository;
import com.faction.clientportal.repository.SurveyTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssessmentSurveyServiceTest {

    @Mock
    private AssessmentSurveyRepository repository;

    @Mock
    private SurveyTemplateRepository templateRepository;

    @Mock
    private AssessmentRepository assessmentRepository;

    @Mock
    private ApplicationService applicationService;

    @Mock
    private com.faction.clientportal.service.AccessScopeService accessScopeService;

    @InjectMocks
    private AssessmentSurveyService service;

    private SurveyTemplate template;
    private Assessment assessment;
    private AssessmentSurvey survey;

    @BeforeEach
    void setUp() {
        template = SurveyTemplate.builder()
                .id("template-1")
                .name("Architecture Review")
                .questions(new ArrayList<>())
                .active(true)
                .build();

        assessment = Assessment.builder()
                .id("assessment-1")
                .name("Q3 Pentest")
                .applicationId("app-1")
                .build();

        survey = AssessmentSurvey.builder()
                .id("survey-1")
                .assessmentId("assessment-1")
                .templateId("template-1")
                .templateName("Architecture Review")
                .status(SurveyStatus.INCOMPLETE)
                .responses(new ArrayList<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void addToAssessment_announcesSurveyWithLinkInApplicationChat() {
        when(templateRepository.findById("template-1")).thenReturn(Optional.of(template));
        when(repository.save(any(AssessmentSurvey.class))).thenReturn(survey);
        when(assessmentRepository.findByIdAndDeletedAtIsNull("assessment-1"))
                .thenReturn(Optional.of(assessment));

        AddAssessmentSurveyRequest request = new AddAssessmentSurveyRequest();
        request.setTemplateId("template-1");

        AssessmentSurveyDto result = service.addToAssessment("assessment-1", request, "user-1");

        assertThat(result).isNotNull();
        verify(applicationService).addSystemComment(
                eq("app-1"),
                contains("**Survey assigned**"),
                eq("user-1"));
        verify(applicationService).addSystemComment(
                eq("app-1"),
                contains("/applications?tab=assessments&assessment=assessment-1&survey=survey-1"),
                eq("user-1"));
    }

    @Test
    void addToAssessment_throwsWhenTemplateMissing() {
        when(templateRepository.findById("missing")).thenReturn(Optional.empty());

        AddAssessmentSurveyRequest request = new AddAssessmentSurveyRequest();
        request.setTemplateId("missing");

        assertThatThrownBy(() -> service.addToAssessment("assessment-1", request, "user-1"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(applicationService, never()).addSystemComment(any(), any(), any());
    }

    @Test
    void updateSurvey_completionAnnouncedInApplicationChat() {
        when(repository.findById("survey-1")).thenReturn(Optional.of(survey));
        when(repository.save(any(AssessmentSurvey.class))).thenAnswer(inv -> inv.getArgument(0));
        when(assessmentRepository.findByIdAndDeletedAtIsNull("assessment-1"))
                .thenReturn(Optional.of(assessment));

        UpdateAssessmentSurveyRequest request = new UpdateAssessmentSurveyRequest();
        request.setComplete(true);

        AssessmentSurveyDto result = service.updateSurvey("assessment-1", "survey-1", request, "user-1");

        assertThat(result.getStatus()).hasToString("COMPLETE");
        verify(applicationService).addSystemComment(
                eq("app-1"),
                contains("**Survey completed**"),
                eq("user-1"));
    }

    @Test
    void updateSurvey_alreadyCompleteNotReAnnounced() {
        survey.setStatus(SurveyStatus.COMPLETE);
        when(repository.findById("survey-1")).thenReturn(Optional.of(survey));
        when(repository.save(any(AssessmentSurvey.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateAssessmentSurveyRequest request = new UpdateAssessmentSurveyRequest();
        request.setComplete(true);

        service.updateSurvey("assessment-1", "survey-1", request, "user-1");

        verify(applicationService, never()).addSystemComment(any(), any(), any());
    }

    @Test
    void updateSurvey_markingIncompleteNotAnnounced() {
        survey.setStatus(SurveyStatus.COMPLETE);
        when(repository.findById("survey-1")).thenReturn(Optional.of(survey));
        when(repository.save(any(AssessmentSurvey.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateAssessmentSurveyRequest request = new UpdateAssessmentSurveyRequest();
        request.setComplete(false);

        AssessmentSurveyDto result = service.updateSurvey("assessment-1", "survey-1", request, "user-1");

        assertThat(result.getStatus()).hasToString("INCOMPLETE");
        verify(applicationService, never()).addSystemComment(any(), any(), any());
    }
}
