package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response to a key-creation request. Carries the one-time plaintext {@code key} — the only moment
 * the secret is ever exposed; it is not stored and cannot be retrieved again — alongside the
 * key's non-secret metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateApiKeyResponse {

    /** The full plaintext key ({@code sk_fac_…}). Shown once; store it now or it is lost. */
    private String key;

    private ApiKeyDto apiKey;
}
