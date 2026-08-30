package com.faction.clientportal.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class CreateChecklistTemplateRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String assessmentTypeId;

    private List<ChecklistTemplateQuestionDto> questions;

    private boolean preventClosure;
}
