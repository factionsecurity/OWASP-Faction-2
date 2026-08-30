package com.faction.clientportal.dto;

import lombok.Data;

import java.util.List;

@Data
public class UpdateAssessmentChecklistRequest {
    private List<ChecklistResponseDto> responses;
}
