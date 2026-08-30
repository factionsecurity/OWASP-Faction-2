package com.faction.clientportal.dto;

import com.faction.clientportal.model.ChecklistTemplateQuestion;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChecklistTemplateQuestionDto {
    private String id;
    private String text;
    private int order;

    public static ChecklistTemplateQuestionDto fromEntity(ChecklistTemplateQuestion entity) {
        return ChecklistTemplateQuestionDto.builder()
                .id(entity.getId())
                .text(entity.getText())
                .order(entity.getOrder())
                .build();
    }
}
