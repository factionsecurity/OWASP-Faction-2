package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One application a user is assigned to, with the access level. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserApplicationAssignmentDto {
    private String applicationId;
    private String applicationName;
    private String organizationId;
    private String accessLevel; // "READ" or "WRITE"
}
