package com.faction.clientportal.dto;

import lombok.Data;

import java.util.List;

@Data
public class UpdateChecklistTemplateRequest {
    private String name;
    private String assessmentTypeId;
    private List<ChecklistTemplateQuestionDto> questions;
    private Boolean active;
    private Boolean preventClosure;
}
