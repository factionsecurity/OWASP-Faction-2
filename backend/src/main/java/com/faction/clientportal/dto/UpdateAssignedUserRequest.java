package com.faction.clientportal.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateAssignedUserRequest {
    @NotBlank
    private String accessLevel; // "READ" or "WRITE"
}
