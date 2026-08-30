package com.faction.clientportal.dto;

import com.faction.clientportal.model.AssessmentSurvey;
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
public class AssessmentSurveyDto {
    private String id;
    private String assessmentId;
    private String templateId;
    private String templateName;
    private String status;
    private List<SurveyResponseDto> responses;
    private String completedBy;
    private Instant completedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public static AssessmentSurveyDto fromEntity(AssessmentSurvey entity) {
        return AssessmentSurveyDto.builder()
                .id(entity.getId())
                .assessmentId(entity.getAssessmentId())
                .templateId(entity.getTemplateId())
                .templateName(entity.getTemplateName())
                .status(entity.getStatus() != null ? entity.getStatus().name() : null)
                .responses(entity.getResponses() == null ? List.of() :
                        entity.getResponses().stream()
                                .map(SurveyResponseDto::fromEntity)
                                .collect(Collectors.toList()))
                .completedBy(entity.getCompletedBy())
                .completedAt(entity.getCompletedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
