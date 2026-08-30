package com.faction.clientportal.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AddAssessmentChecklistRequest {

    @NotBlank
    private String templateId;
}
