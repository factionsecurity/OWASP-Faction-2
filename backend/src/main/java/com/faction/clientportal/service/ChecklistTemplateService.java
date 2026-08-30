package com.faction.clientportal.service;

import com.faction.clientportal.dto.ChecklistTemplateDto;
import com.faction.clientportal.dto.ChecklistTemplateQuestionDto;
import com.faction.clientportal.dto.CreateChecklistTemplateRequest;
import com.faction.clientportal.dto.UpdateChecklistTemplateRequest;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.ChecklistTemplate;
import com.faction.clientportal.model.ChecklistTemplateQuestion;
import com.faction.clientportal.repository.ChecklistTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChecklistTemplateService {

    private final ChecklistTemplateRepository repository;

    public List<ChecklistTemplateDto> getAll() {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .map(ChecklistTemplateDto::fromEntity)
                .collect(Collectors.toList());
    }

    public List<ChecklistTemplateDto> getByAssessmentType(String assessmentTypeId) {
        return repository.findByAssessmentTypeIdAndActiveTrue(assessmentTypeId).stream()
                .map(ChecklistTemplateDto::fromEntity)
                .collect(Collectors.toList());
    }

    public ChecklistTemplateDto getById(String id) {
        ChecklistTemplate template = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Checklist template not found: " + id));
        return ChecklistTemplateDto.fromEntity(template);
    }

    public ChecklistTemplateDto create(CreateChecklistTemplateRequest req, String username) {
        List<ChecklistTemplateQuestion> questions = buildQuestions(req.getQuestions());

        ChecklistTemplate template = ChecklistTemplate.builder()
                .name(req.getName())
                .assessmentTypeId(req.getAssessmentTypeId())
                .questions(questions)
                .active(true)
                .preventClosure(req.isPreventClosure())
                .createdBy(username)
                .lastUpdatedBy(username)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        return ChecklistTemplateDto.fromEntity(repository.save(template));
    }

    public ChecklistTemplateDto update(String id, UpdateChecklistTemplateRequest req, String username) {
        ChecklistTemplate template = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Checklist template not found: " + id));

        if (req.getName() != null) template.setName(req.getName());
        if (req.getAssessmentTypeId() != null) template.setAssessmentTypeId(req.getAssessmentTypeId());
        if (req.getQuestions() != null) template.setQuestions(buildQuestions(req.getQuestions()));
        if (req.getActive() != null) template.setActive(req.getActive());
        if (req.getPreventClosure() != null) template.setPreventClosure(req.getPreventClosure());

        template.setLastUpdatedBy(username);
        template.setUpdatedAt(Instant.now());

        return ChecklistTemplateDto.fromEntity(repository.save(template));
    }

    public void delete(String id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("Checklist template not found: " + id);
        }
        repository.deleteById(id);
    }

    private List<ChecklistTemplateQuestion> buildQuestions(List<ChecklistTemplateQuestionDto> dtos) {
        if (dtos == null) return List.of();
        return dtos.stream()
                .map(dto -> ChecklistTemplateQuestion.builder()
                        .id(dto.getId() == null || dto.getId().isBlank() ? UUID.randomUUID().toString() : dto.getId())
                        .text(dto.getText())
                        .order(dto.getOrder())
                        .build())
                .collect(Collectors.toList());
    }
}
