package com.faction.clientportal.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class UpdateRetestRequest {
    private LocalDateTime scheduledStartDate;
    private LocalDateTime scheduledEndDate;
    private List<String> assignedAssessorIds;
    private String scope;
    private String comment;
    private String status;

    /**
     * Revised ratings for the underlying vulnerability, applied when the retest is saved. Null
     * means unchanged. Handled here rather than through the vulnerability API because a retest
     * runs on a finalized assessment, which that API refuses to modify.
     */
    private String severity;
    private String likelihood;
    private String impact;
}
