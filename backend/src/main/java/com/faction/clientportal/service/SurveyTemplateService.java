package com.faction.clientportal.service;

import com.faction.clientportal.dto.CreateSurveyTemplateRequest;
import com.faction.clientportal.dto.SurveyTemplateDto;
import com.faction.clientportal.dto.SurveyTemplateQuestionDto;
import com.faction.clientportal.dto.UpdateSurveyTemplateRequest;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.SurveyFieldType;
import com.faction.clientportal.model.SurveyTemplate;
import com.faction.clientportal.model.SurveyTemplateQuestion;
import com.faction.clientportal.repository.SurveyTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SurveyTemplateService {

    private final SurveyTemplateRepository repository;

    public List<SurveyTemplateDto> getAll() {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .map(SurveyTemplateDto::fromEntity)
                .collect(Collectors.toList());
    }

    public List<SurveyTemplateDto> getActive() {
        return repository.findByActiveTrueOrderByCreatedAtDesc().stream()
                .map(SurveyTemplateDto::fromEntity)
                .collect(Collectors.toList());
    }

    public SurveyTemplateDto getById(String id) {
        return SurveyTemplateDto.fromEntity(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Survey template not found: " + id)));
    }

    public SurveyTemplateDto create(CreateSurveyTemplateRequest req, String username) {
        SurveyTemplate template = SurveyTemplate.builder()
                .name(req.getName())
                .questions(buildQuestions(req.getQuestions()))
                .active(true)
                .createdBy(username)
                .lastUpdatedBy(username)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        return SurveyTemplateDto.fromEntity(repository.save(template));
    }

    public SurveyTemplateDto update(String id, UpdateSurveyTemplateRequest req, String username) {
        SurveyTemplate template = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Survey template not found: " + id));

        if (req.getName() != null) template.setName(req.getName());
        if (req.getQuestions() != null) template.setQuestions(buildQuestions(req.getQuestions()));
        if (req.getActive() != null) template.setActive(req.getActive());

        template.setLastUpdatedBy(username);
        template.setUpdatedAt(Instant.now());

        return SurveyTemplateDto.fromEntity(repository.save(template));
    }

    public void delete(String id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("Survey template not found: " + id);
        }
        repository.deleteById(id);
    }

    private List<SurveyTemplateQuestion> buildQuestions(List<SurveyTemplateQuestionDto> dtos) {
        if (dtos == null) return List.of();
        return dtos.stream()
                .map(dto -> SurveyTemplateQuestion.builder()
                        .id(dto.getId() == null || dto.getId().isBlank() ? UUID.randomUUID().toString() : dto.getId())
                        .text(dto.getText())
                        .fieldType(dto.getFieldType() != null ? SurveyFieldType.valueOf(dto.getFieldType()) : SurveyFieldType.TEXTAREA)
                        .dropdownOptions(dto.getDropdownOptions())
                        .order(dto.getOrder())
                        .build())
                .collect(Collectors.toList());
    }
}
