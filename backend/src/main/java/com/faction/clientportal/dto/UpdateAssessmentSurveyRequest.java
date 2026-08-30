package com.faction.clientportal.dto;

import lombok.Data;

import java.util.List;

@Data
public class UpdateAssessmentSurveyRequest {

    private List<SurveyResponseDto> responses;

    private Boolean complete;
}
