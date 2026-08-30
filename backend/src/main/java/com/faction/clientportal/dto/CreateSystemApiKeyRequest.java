package com.faction.clientportal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request to mint a system (service-account) API key. Unlike a user key, a system key has no owner
 * to inherit permissions from, so its authorities come entirely from {@link #permissions} — an
 * empty/omitted list produces an inert key (authenticates, but authorized for nothing).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateSystemApiKeyRequest {

    @NotBlank(message = "API key name is required")
    @Size(max = 255, message = "API key name must be at most 255 characters")
    private String name;

    /** Permission strings granted to the key (same vocabulary as {@code Role.permissions}). */
    private List<String> permissions;
}
