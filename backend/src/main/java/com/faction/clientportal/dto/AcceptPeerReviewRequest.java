package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AcceptPeerReviewRequest {

    /**
     * Field IDs of assessment-level fields whose revised values should be applied
     * to the live Assessment document.
     */
    private List<String> acceptedAssessmentFieldIds;

    /**
     * Per vulnerability: map of vulnerabilityId → list of field names accepted.
     * Field names: "description", "recommendation", "details", or a custom field ID.
     */
    private Map<String, List<String>> acceptedVulnerabilityChanges;
}
