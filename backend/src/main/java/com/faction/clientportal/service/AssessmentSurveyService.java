package com.faction.clientportal.service;

import com.faction.clientportal.dto.AddAssessmentSurveyRequest;
import com.faction.clientportal.dto.AssessmentSurveyDto;
import com.faction.clientportal.dto.UpdateAssessmentSurveyRequest;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentSurvey;
import com.faction.clientportal.model.SurveyResponse;
import com.faction.clientportal.model.SurveyStatus;
import com.faction.clientportal.model.SurveyTemplate;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentSurveyRepository;
import com.faction.clientportal.repository.SurveyTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AssessmentSurveyService {

    private final AssessmentSurveyRepository repository;
    private final AccessScopeService accessScopeService;
    private final SurveyTemplateRepository templateRepository;
    private final AssessmentRepository assessmentRepository;
    private final ApplicationService applicationService;

    public List<AssessmentSurveyDto> getByAssessment(String assessmentId) {
        return getByAssessment(assessmentId, null);
    }

    public List<AssessmentSurveyDto> getByAssessment(String assessmentId,
            org.springframework.security.core.Authentication authentication) {
        assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId)
                .ifPresent(a -> accessScopeService.checkAssessmentAccess(authentication, a));
        return repository.findByAssessmentId(assessmentId).stream()
                .map(AssessmentSurveyDto::fromEntity)
                .collect(Collectors.toList());
    }

    public AssessmentSurveyDto addToAssessment(String assessmentId, AddAssessmentSurveyRequest req, String username) {
        SurveyTemplate template = templateRepository.findById(req.getTemplateId())
                .orElseThrow(() -> new ResourceNotFoundException("Survey template not found: " + req.getTemplateId()));

        List<SurveyResponse> responses = template.getQuestions() == null ? List.of() :
                template.getQuestions().stream()
                        .map(q -> SurveyResponse.builder()
                                .questionId(q.getId())
                                .questionText(q.getText())
                                .fieldType(q.getFieldType())
                                .dropdownOptions(q.getDropdownOptions())
                                .answer(null)
                                .order(q.getOrder())
                                .build())
                        .collect(Collectors.toList());

        AssessmentSurvey survey = AssessmentSurvey.builder()
                .assessmentId(assessmentId)
                .templateId(template.getId())
                .templateName(template.getName())
                .status(SurveyStatus.INCOMPLETE)
                .responses(responses)
                .createdBy(username)
                .lastUpdatedBy(username)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        AssessmentSurvey saved = repository.save(survey);

        // Announce the new survey in the application's chat, with a deep link to complete it
        assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId).ifPresent(assessment -> {
            String surveyLink = "/applications?tab=assessments&assessment=" + assessmentId
                    + "&survey=" + saved.getId();
            applicationService.addSystemComment(assessment.getApplicationId(),
                    "**Survey assigned**: \"" + template.getName() + "\" was added to assessment \""
                            + assessment.getName() + "\" by {actor}. [Complete the survey](" + surveyLink + ")",
                    username);
        });

        return AssessmentSurveyDto.fromEntity(saved);
    }

    public AssessmentSurveyDto updateSurvey(String assessmentId, String surveyId,
                                             UpdateAssessmentSurveyRequest req, String username) {
        return updateSurvey(assessmentId, surveyId, req, username, null);
    }

    public AssessmentSurveyDto updateSurvey(String assessmentId, String surveyId,
                                             UpdateAssessmentSurveyRequest req, String username,
                                             org.springframework.security.core.Authentication authentication) {
        assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId)
                .ifPresent(a -> accessScopeService.checkAssessmentAccess(authentication, a));
        AssessmentSurvey survey = repository.findById(surveyId)
                .orElseThrow(() -> new ResourceNotFoundException("Assessment survey not found: " + surveyId));

        if (!survey.getAssessmentId().equals(assessmentId)) {
            throw new ResourceNotFoundException("Assessment survey not found: " + surveyId);
        }

        if (req.getResponses() != null) {
            List<SurveyResponse> responses = req.getResponses().stream()
                    .map(dto -> {
                        SurveyResponse existing = survey.getResponses() == null ? null :
                                survey.getResponses().stream()
                                        .filter(r -> r.getQuestionId().equals(dto.getQuestionId()))
                                        .findFirst().orElse(null);
                        if (existing != null) {
                            existing.setAnswer(dto.getAnswer());
                            return existing;
                        }
                        return dto.toEntity();
                    })
                    .collect(Collectors.toList());
            survey.setResponses(responses);
        }

        boolean newlyCompleted = false;
        if (Boolean.TRUE.equals(req.getComplete())) {
            newlyCompleted = survey.getStatus() != SurveyStatus.COMPLETE;
            survey.setStatus(SurveyStatus.COMPLETE);
            survey.setCompletedBy(username);
            survey.setCompletedAt(Instant.now());
        } else if (Boolean.FALSE.equals(req.getComplete())) {
            survey.setStatus(SurveyStatus.INCOMPLETE);
            survey.setCompletedBy(null);
            survey.setCompletedAt(null);
        }

        survey.setLastUpdatedBy(username);
        survey.setUpdatedAt(Instant.now());

        AssessmentSurvey saved = repository.save(survey);

        // Announce completion in the application's chat (only on the incomplete → complete transition)
        if (newlyCompleted) {
            assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId).ifPresent(assessment ->
                    applicationService.addSystemComment(assessment.getApplicationId(),
                            "**Survey completed**: \"" + saved.getTemplateName() + "\" for assessment \""
                                    + assessment.getName() + "\" was completed by {actor}.",
                            username));
        }

        return AssessmentSurveyDto.fromEntity(saved);
    }

    public void removeFromAssessment(String assessmentId, String surveyId) {
        AssessmentSurvey survey = repository.findById(surveyId)
                .orElseThrow(() -> new ResourceNotFoundException("Assessment survey not found: " + surveyId));

        if (!survey.getAssessmentId().equals(assessmentId)) {
            throw new ResourceNotFoundException("Assessment survey not found: " + surveyId);
        }

        repository.deleteById(surveyId);
    }
}
