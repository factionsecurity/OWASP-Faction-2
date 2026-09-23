package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Outcome of a committed assessment CSV import. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentImportResultDto {
    private int created;
    @Builder.Default
    private List<String> createdApplications = new ArrayList<>();
    @Builder.Default
    private List<String> createdCampaigns = new ArrayList<>();
    @Builder.Default
    private List<String> assessmentIds = new ArrayList<>();
}
