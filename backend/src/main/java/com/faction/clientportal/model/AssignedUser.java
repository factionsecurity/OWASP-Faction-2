package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignedUser {
    private String userId;
    private String displayName;
    private String email;
    private String accessLevel; // "READ" or "WRITE"
}
