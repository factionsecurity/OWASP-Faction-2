package com.faction.clientportal.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class CreateRetestRequest {
    @NotBlank
    private String vulnerabilityId;

    /** Both dates absent = a retest REQUEST (e.g. from an app owner) that staff
     *  schedule later; both present = a directly SCHEDULED retest. */
    private LocalDateTime scheduledStartDate;

    private LocalDateTime scheduledEndDate;

    private List<String> assignedAssessorIds = new ArrayList<>();

    private String scope;
    private String comment;
}
