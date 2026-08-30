package com.faction.clientportal.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AddAssessmentSurveyRequest {

    @NotBlank
    private String templateId;
}
