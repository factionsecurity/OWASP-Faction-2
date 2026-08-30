package com.faction.clientportal.dto;

import com.faction.clientportal.model.SurveyFieldType;
import com.faction.clientportal.model.SurveyTemplateQuestion;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SurveyTemplateQuestionDto {
    private String id;
    private String text;
    private String fieldType;
    private List<String> dropdownOptions;
    private int order;

    public static SurveyTemplateQuestionDto fromEntity(SurveyTemplateQuestion entity) {
        return SurveyTemplateQuestionDto.builder()
                .id(entity.getId())
                .text(entity.getText())
                .fieldType(entity.getFieldType() != null ? entity.getFieldType().name() : null)
                .dropdownOptions(entity.getDropdownOptions())
                .order(entity.getOrder())
                .build();
    }

    public SurveyTemplateQuestion toEntity() {
        return SurveyTemplateQuestion.builder()
                .id(this.id)
                .text(this.text)
                .fieldType(this.fieldType != null ? SurveyFieldType.valueOf(this.fieldType) : null)
                .dropdownOptions(this.dropdownOptions)
                .order(this.order)
                .build();
    }
}
