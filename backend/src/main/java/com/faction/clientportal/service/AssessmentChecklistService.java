package com.faction.clientportal.service;

import com.faction.clientportal.dto.AddAssessmentChecklistRequest;
import com.faction.clientportal.dto.AssessmentChecklistDto;
import com.faction.clientportal.dto.ChecklistResponseDto;
import com.faction.clientportal.dto.UpdateAssessmentChecklistRequest;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.AssessmentChecklist;
import com.faction.clientportal.model.ChecklistResponse;
import com.faction.clientportal.model.ChecklistTemplate;
import com.faction.clientportal.repository.AssessmentChecklistRepository;
import com.faction.clientportal.repository.ChecklistTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AssessmentChecklistService {

    private final AssessmentChecklistRepository repository;
    private final ChecklistTemplateRepository templateRepository;

    public List<AssessmentChecklistDto> getByAssessment(String assessmentId) {
        return repository.findByAssessmentId(assessmentId).stream()
                .map(AssessmentChecklistDto::fromEntity)
                .collect(Collectors.toList());
    }

    public AssessmentChecklistDto addToAssessment(String assessmentId, AddAssessmentChecklistRequest req, String username) {
        ChecklistTemplate template = templateRepository.findById(req.getTemplateId())
                .orElseThrow(() -> new ResourceNotFoundException("Checklist template not found: " + req.getTemplateId()));

        List<ChecklistResponse> responses = template.getQuestions() == null ? List.of() :
                template.getQuestions().stream()
                        .map(q -> ChecklistResponse.builder()
                                .questionId(q.getId())
                                .questionText(q.getText())
                                .result(null)
                                .comment(null)
                                .order(q.getOrder())
                                .build())
                        .collect(Collectors.toList());

        AssessmentChecklist checklist = AssessmentChecklist.builder()
                .assessmentId(assessmentId)
                .templateId(template.getId())
                .templateName(template.getName())
                .responses(responses)
                .createdBy(username)
                .lastUpdatedBy(username)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        return AssessmentChecklistDto.fromEntity(repository.save(checklist));
    }

    public AssessmentChecklistDto updateResponses(String assessmentId, String checklistId,
                                                   UpdateAssessmentChecklistRequest req, String username) {
        AssessmentChecklist checklist = repository.findById(checklistId)
                .orElseThrow(() -> new ResourceNotFoundException("Assessment checklist not found: " + checklistId));

        if (!checklist.getAssessmentId().equals(assessmentId)) {
            throw new ResourceNotFoundException("Assessment checklist not found: " + checklistId);
        }

        if (req.getResponses() != null) {
            List<ChecklistResponse> responses = req.getResponses().stream()
                    .map(ChecklistResponseDto::toEntity)
                    .collect(Collectors.toList());
            checklist.setResponses(responses);
        }

        checklist.setLastUpdatedBy(username);
        checklist.setUpdatedAt(Instant.now());

        return AssessmentChecklistDto.fromEntity(repository.save(checklist));
    }

    public void removeFromAssessment(String assessmentId, String checklistId) {
        AssessmentChecklist checklist = repository.findById(checklistId)
                .orElseThrow(() -> new ResourceNotFoundException("Assessment checklist not found: " + checklistId));

        if (!checklist.getAssessmentId().equals(assessmentId)) {
            throw new ResourceNotFoundException("Assessment checklist not found: " + checklistId);
        }

        repository.deleteById(checklistId);
    }
}
