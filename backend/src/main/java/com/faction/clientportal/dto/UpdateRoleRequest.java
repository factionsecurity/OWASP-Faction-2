package com.faction.clientportal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateRoleRequest {

    @NotBlank(message = "Role name is required")
    private String name;

    private String description;

    @NotNull(message = "Permissions list cannot be null")
    private List<String> permissions;

    /** When true, the role is assignable to external (client) users. */
    @Builder.Default
    private boolean externalRole = false;
}
