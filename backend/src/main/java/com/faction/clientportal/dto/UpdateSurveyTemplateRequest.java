package com.faction.clientportal.dto;

import lombok.Data;

import java.util.List;

@Data
public class UpdateSurveyTemplateRequest {

    private String name;

    private List<SurveyTemplateQuestionDto> questions;

    private Boolean active;
}
