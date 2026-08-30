package com.faction.clientportal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request to update a system (service-account) API key. Full-replace semantics: {@link #name} and
 * {@link #permissions} together define the key's new state. System keys are {@code CUSTOM}-scoped —
 * the stored list IS the key's authorities — so permissions are unrestricted (super_admin-gated at
 * the endpoint); an empty/omitted list leaves the key inert. Does not change the secret.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateSystemApiKeyRequest {

    @NotBlank(message = "API key name is required")
    @Size(max = 255, message = "API key name must be at most 255 characters")
    private String name;

    /** New permission strings for the key (same vocabulary as {@code Role.permissions}). */
    private List<String> permissions;
}
