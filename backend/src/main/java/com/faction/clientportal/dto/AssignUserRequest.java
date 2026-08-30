package com.faction.clientportal.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssignUserRequest {
    @NotBlank
    private String userId;
    @NotBlank
    private String accessLevel; // "READ" or "WRITE"
}
