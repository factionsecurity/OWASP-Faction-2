package com.faction.clientportal.dto;

import com.faction.clientportal.model.AssessmentChecklist;
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
public class AssessmentChecklistDto {
    private String id;
    private String assessmentId;
    private String templateId;
    private String templateName;
    private List<ChecklistResponseDto> responses;
    private Instant createdAt;
    private Instant updatedAt;

    public static AssessmentChecklistDto fromEntity(AssessmentChecklist entity) {
        return AssessmentChecklistDto.builder()
                .id(entity.getId())
                .assessmentId(entity.getAssessmentId())
                .templateId(entity.getTemplateId())
                .templateName(entity.getTemplateName())
                .responses(entity.getResponses() == null ? List.of() :
                        entity.getResponses().stream()
                                .map(ChecklistResponseDto::fromEntity)
                                .collect(Collectors.toList()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
