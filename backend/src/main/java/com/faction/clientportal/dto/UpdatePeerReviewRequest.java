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
public class UpdatePeerReviewRequest {

    /** Reviewer's edits to assessment-level fields (key = fieldId) */
    private Map<String, String> revisedFieldValues;

    /** Reviewer notes per assessment-level field (key = fieldId) */
    private Map<String, String> fieldNotes;

    /** Reviewer's edits/notes per vulnerability */
    private List<PeerReviewVulnerabilityDto> vulnerabilities;
}
