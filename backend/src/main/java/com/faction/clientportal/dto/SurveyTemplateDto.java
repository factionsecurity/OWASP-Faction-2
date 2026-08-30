package com.faction.clientportal.dto;

import com.faction.clientportal.model.SurveyTemplate;
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
public class SurveyTemplateDto {
    private String id;
    private String name;
    private List<SurveyTemplateQuestionDto> questions;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;

    public static SurveyTemplateDto fromEntity(SurveyTemplate entity) {
        return SurveyTemplateDto.builder()
                .id(entity.getId())
                .name(entity.getName())
                .questions(entity.getQuestions() == null ? List.of() :
                        entity.getQuestions().stream()
                                .map(SurveyTemplateQuestionDto::fromEntity)
                                .collect(Collectors.toList()))
                .active(entity.isActive())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
