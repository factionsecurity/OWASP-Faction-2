package com.faction.clientportal.dto;

import com.faction.clientportal.model.ChecklistResponse;
import com.faction.clientportal.model.ChecklistResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChecklistResponseDto {
    private String questionId;
    private String questionText;
    private String result;
    private String comment;
    private int order;

    public static ChecklistResponseDto fromEntity(ChecklistResponse entity) {
        return ChecklistResponseDto.builder()
                .questionId(entity.getQuestionId())
                .questionText(entity.getQuestionText())
                .result(entity.getResult() != null ? entity.getResult().name() : null)
                .comment(entity.getComment())
                .order(entity.getOrder())
                .build();
    }

    public ChecklistResponse toEntity() {
        return ChecklistResponse.builder()
                .questionId(this.questionId)
                .questionText(this.questionText)
                .result(this.result != null ? ChecklistResult.valueOf(this.result) : null)
                .comment(this.comment)
                .order(this.order)
                .build();
    }
}
