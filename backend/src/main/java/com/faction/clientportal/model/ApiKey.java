package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A programmatic API key, presented by clients as {@code Authorization: Bearer sk_fac_<random>}.
 *
 * <p>Only the SHA-256 hash of the full key ({@link #tokenHash}) is ever persisted — the plaintext
 * secret is shown once at creation and never stored.
 *
 * <p>Type/owner invariant (enforced in {@code ApiKeyService}, the only writer): a {@code USER} key
 * always has a {@link #userId}; a {@code SYSTEM} key never does.
 *
 * <p>Temporal fields use {@link Instant} (a true point on the timeline) rather than
 * {@code LocalDateTime}, so timestamps are unambiguously UTC regardless of the JVM's default
 * timezone — consistent with {@code hibernate.jdbc.time_zone=UTC}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "api_keys", indexes = {
    @Index(name = "idx_api_keys_token_hash", columnList = "token_hash", unique = true),
    @Index(name = "idx_api_keys_user_id", columnList = "user_id"),
    @Index(name = "idx_api_keys_key_type", columnList = "key_type")
})
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApiKeyType keyType;

    /**
     * How authorities are resolved at authentication time — see {@link ApiKeyScope}. Invariant
     * (enforced in {@code ApiKeyService}): {@code USER} → {@code READ_WRITE}/{@code READ_ONLY},
     * {@code SYSTEM} → {@code CUSTOM}. DDL-nullable only so {@code ddl-auto: update} can add the
     * column to existing tables; the service always writes it, and rows predating the column
     * resolve to their type's natural scope ({@code USER} → {@code READ_WRITE}, {@code SYSTEM} →
     * {@code CUSTOM}).
     */
    @Enumerated(EnumType.STRING)
    private ApiKeyScope scope;

    /** Owner for {@code USER} keys; {@code null} for {@code SYSTEM} keys. */
    private String userId;

    @Column(unique = true, nullable = false)
    private String tokenHash;

    /**
     * Non-secret UI hint (currently the {@code sk_fac_} prefix plus the first 6 characters of the
     * random part, e.g. {@code sk_fac_ab12cd}). Lets users tell their keys apart in a list without
     * ever exposing the secret; not used for authentication (lookup is by {@link #tokenHash}). The
     * exact form is an implementation detail — kept generic so the strategy can change later.
     */
    private String hint;

    /**
     * Permissions explicitly assigned to this key (same vocabulary as {@code Role.permissions}).
     * Only meaningful for {@link ApiKeyScope#CUSTOM} (system) keys — the stored list IS the key's
     * authorities. User keys never store permissions; their authorities are derived live from the
     * owner per {@link #scope}.
     */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> permissions = new ArrayList<>();

    private Instant createdAt;

    /** Reserved for a future expiry feature; not settable or enforced in v1. */
    private Instant expiresAt;

    private Instant revokedAt;

    private Instant lastUsedAt;
}
