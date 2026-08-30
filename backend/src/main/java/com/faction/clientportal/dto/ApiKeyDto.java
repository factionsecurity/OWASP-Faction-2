package com.faction.clientportal.dto;

import com.faction.clientportal.model.ApiKey;
import com.faction.clientportal.model.ApiKeyScope;
import com.faction.clientportal.model.ApiKeyType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Non-secret view of an {@link ApiKey}. Deliberately omits the token hash and the plaintext secret
 * (the secret is only ever returned once, via {@link CreateApiKeyResponse}).
 *
 * <p>{@code expiresAt} is intentionally not exposed: the column exists and authentication enforces
 * it if set, but there is no way to set an expiry yet, so it would always be null. Add it back here
 * alongside the create/update parameter when expiration becomes a real feature.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKeyDto {

    private String id;
    private String name;
    private ApiKeyType keyType;
    /** How authorities are resolved: READ_WRITE/READ_ONLY (user keys) or CUSTOM (system keys). */
    private ApiKeyScope scope;
    /** Non-secret hint (e.g. {@code sk_fac_ab12cd}) so users can tell keys apart. */
    private String hint;
    /** Stored permissions — only meaningful for CUSTOM (system) keys; empty for user keys. */
    private List<String> permissions;
    private Instant createdAt;
    private Instant lastUsedAt;

    public static ApiKeyDto from(ApiKey key) {
        return ApiKeyDto.builder()
                .id(key.getId())
                .name(key.getName())
                .keyType(key.getKeyType())
                .scope(key.getScope())
                .hint(key.getHint())
                .permissions(key.getPermissions())
                .createdAt(key.getCreatedAt())
                .lastUsedAt(key.getLastUsedAt())
                .build();
    }
}
