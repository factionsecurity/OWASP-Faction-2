package com.faction.clientportal.dto;

import com.faction.clientportal.model.SurveyFieldType;
import com.faction.clientportal.model.SurveyResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SurveyResponseDto {
    private String questionId;
    private String questionText;
    private String fieldType;
    private List<String> dropdownOptions;
    private String answer;
    private int order;

    public static SurveyResponseDto fromEntity(SurveyResponse entity) {
        return SurveyResponseDto.builder()
                .questionId(entity.getQuestionId())
                .questionText(entity.getQuestionText())
                .fieldType(entity.getFieldType() != null ? entity.getFieldType().name() : null)
                .dropdownOptions(entity.getDropdownOptions())
                .answer(entity.getAnswer())
                .order(entity.getOrder())
                .build();
    }

    public SurveyResponse toEntity() {
        return SurveyResponse.builder()
                .questionId(this.questionId)
                .questionText(this.questionText)
                .fieldType(this.fieldType != null ? SurveyFieldType.valueOf(this.fieldType) : null)
                .dropdownOptions(this.dropdownOptions)
                .answer(this.answer)
                .order(this.order)
                .build();
    }
}
