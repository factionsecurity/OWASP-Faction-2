package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Assessments-tab row for the manager dashboard: the standard AssessmentDto
 * plus the display names of the teams its assessors belong to. Kept as a
 * wrapper so the team-display concern stays local to this feature instead of
 * widening the shared AssessmentDto.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManagerDashboardAssessmentDto {
    private AssessmentDto assessment;
    private List<String> teamNames;
}
