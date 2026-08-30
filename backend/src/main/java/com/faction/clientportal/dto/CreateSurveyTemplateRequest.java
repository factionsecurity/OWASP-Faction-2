package com.faction.clientportal.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class CreateSurveyTemplateRequest {

    @NotBlank
    private String name;

    private List<SurveyTemplateQuestionDto> questions;
}
