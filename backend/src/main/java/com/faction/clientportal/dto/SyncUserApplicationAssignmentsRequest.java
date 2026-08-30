package com.faction.clientportal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Replaces a user's full set of application assignments. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncUserApplicationAssignmentsRequest {

    @Valid
    private List<Assignment> assignments = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Assignment {
        @NotBlank
        private String applicationId;
        @NotBlank
        private String accessLevel; // "READ" or "WRITE"
    }
}
