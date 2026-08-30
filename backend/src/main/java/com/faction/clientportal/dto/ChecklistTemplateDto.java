package com.faction.clientportal.dto;

import com.faction.clientportal.model.ChecklistTemplate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChecklistTemplateDto {
    private String id;
    private String name;
    private String assessmentTypeId;
    private List<ChecklistTemplateQuestionDto> questions;
    private boolean active;
    private boolean preventClosure;
    private Instant createdAt;
    private Instant updatedAt;

    public static ChecklistTemplateDto fromEntity(ChecklistTemplate entity) {
        return ChecklistTemplateDto.builder()
                .id(entity.getId())
                .name(entity.getName())
                .assessmentTypeId(entity.getAssessmentTypeId())
                .questions(entity.getQuestions() == null ? List.of() :
                        entity.getQuestions().stream()
                                .map(ChecklistTemplateQuestionDto::fromEntity)
                                .collect(Collectors.toList()))
                .active(entity.isActive())
                .preventClosure(entity.isPreventClosure())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
