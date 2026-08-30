package com.faction.clientportal.dto;

import com.faction.clientportal.model.ApiKeyScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateApiKeyRequest {

    @NotBlank(message = "API key name is required")
    @Size(max = 255, message = "API key name must be at most 255 characters")
    private String name;

    /**
     * {@code READ_WRITE} (all of your live permissions — the default when omitted) or
     * {@code READ_ONLY} (the read-only slice of them). Resolved fresh on every request, so the key
     * always tracks your current access. {@code CUSTOM} is rejected for user keys.
     */
    private ApiKeyScope scope;
}
