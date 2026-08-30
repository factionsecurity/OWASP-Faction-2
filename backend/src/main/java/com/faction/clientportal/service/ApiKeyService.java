package com.faction.clientportal.service;

import com.faction.clientportal.model.ApiKey;
import com.faction.clientportal.model.ApiKeyScope;
import com.faction.clientportal.model.ApiKeyType;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.ApiKeyRepository;
import com.faction.clientportal.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Mints, looks up, and authenticates {@link ApiKey}s.
 *
 * <p>A key is presented as {@code Authorization: Bearer sk_fac_<random>}. Only the SHA-256 hash of
 * the full key is stored; the plaintext is returned once at creation and never persisted.
 *
 * <p>SHA-256 is the correct choice for hashing these high-entropy (256-bit) random tokens — a slow
 * password hash would add nothing but latency to the per-request lookup. If the scheme ever needs
 * to change, add a {@code hashVersion} column defaulting existing rows to the current scheme and
 * migrate lazily (the plaintext is gone, so old keys must keep verifying under their original hash).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    /** Identifying prefix on every key — distinguishes API keys from JWTs (which start with {@code eyJ}). */
    public static final String KEY_PREFIX = "sk_fac_";

    private static final int SECRET_BYTES = 32;
    private static final int HINT_RANDOM_CHARS = 6;
    private static final long LAST_USED_THROTTLE_SECONDS = 60;

    private final ApiKeyRepository apiKeyRepository;
    private final UserRepository userRepository;
    private final AuthService authService;

    // ── Generation ───────────────────────────────────────────────────────────

    /** Mint a user-owned key with the default {@link ApiKeyScope#READ_WRITE} scope. */
    public GeneratedApiKey createUserKey(String userId, String name) {
        return createUserKey(userId, name, null);
    }

    /**
     * Mint a user-owned key. Its authorities are always derived live from the owner at
     * authentication time, shaped by {@code scope} ({@code null} defaults to
     * {@link ApiKeyScope#READ_WRITE}). The plaintext secret is returned once, in
     * {@link GeneratedApiKey}.
     *
     * @throws IllegalArgumentException if {@code scope} is {@link ApiKeyScope#CUSTOM} — a
     *         self-service key with a stored permission list is the escalation surface this scope
     *         model exists to remove; enumerated permissions belong to system keys only
     */
    public GeneratedApiKey createUserKey(String userId, String name, ApiKeyScope scope) {
        if (userId == null) {
            throw new IllegalArgumentException("A user key requires an owner");
        }
        ApiKeyScope resolved = requireUserScope(scope);
        String trimmed = name != null ? name.trim() : null;
        if (trimmed != null
                && apiKeyRepository.existsByUserIdAndNameIgnoreCaseAndRevokedAtIsNull(userId, trimmed)) {
            throw new IllegalArgumentException("You already have an API key named '" + trimmed + "'");
        }
        return create(ApiKeyType.USER, userId, trimmed, resolved, null);
    }

    /** Mint an ownerless system (service-account) key with no assigned permissions (inert). */
    public GeneratedApiKey createSystemKey(String name) {
        return createSystemKey(name, null);
    }

    /**
     * Mint an ownerless system (service-account) key with the given assigned permissions. System
     * keys are always {@link ApiKeyScope#CUSTOM}: no owner to derive from, so the stored list IS
     * the key's authorities (an empty/null list yields an inert key that authenticates but is
     * authorized for nothing).
     */
    public GeneratedApiKey createSystemKey(String name, List<String> permissions) {
        validateAssignablePermissions(permissions);
        String trimmed = name != null ? name.trim() : null;
        // Unique among active system keys — a system key's principal is "system:<name>", so a
        // duplicate name would make two credentials indistinguishable in audit logs.
        if (trimmed != null
                && apiKeyRepository.existsByKeyTypeAndNameIgnoreCaseAndRevokedAtIsNull(ApiKeyType.SYSTEM, trimmed)) {
            throw new IllegalArgumentException("A system API key named '" + trimmed + "' already exists");
        }
        return create(ApiKeyType.SYSTEM, null, trimmed, ApiKeyScope.CUSTOM, permissions);
    }

    private GeneratedApiKey create(
            ApiKeyType type, String userId, String name, ApiKeyScope scope, List<String> permissions) {
        String secret = generateSecret();
        ApiKey.ApiKeyBuilder builder = ApiKey.builder()
                .name(name)
                .keyType(type)
                .scope(scope)
                .userId(userId)
                .tokenHash(hash(secret))
                .hint(hint(secret))
                .createdAt(Instant.now());
        if (permissions != null) {
            builder.permissions(new ArrayList<>(permissions));
        }
        ApiKey key = apiKeyRepository.save(builder.build());
        return new GeneratedApiKey(key, secret);
    }

    // ── Authentication ───────────────────────────────────────────────────────

    /**
     * Resolve a presented key to an authenticated principal, or empty if the key is unknown,
     * revoked, expired, or its owner is ineligible. Updates {@code lastUsedAt} (throttled).
     */
    public Optional<ApiKeyPrincipal> authenticate(String rawKey) {
        if (rawKey == null || !rawKey.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        ApiKey key = apiKeyRepository.findByTokenHash(hash(rawKey)).orElse(null);
        if (key == null || key.getRevokedAt() != null) {
            return Optional.empty();
        }
        if (key.getExpiresAt() != null && key.getExpiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }

        String principal;
        List<String> authorities;

        if (key.getKeyType() == ApiKeyType.USER) {
            User owner = userRepository.findById(key.getUserId()).orElse(null);
            // Same gating as interactive login, plus internal-only (external users get no keys).
            if (owner == null
                    || owner.getDeletedAt() != null
                    || owner.getDisabledAt() != null
                    || !Boolean.TRUE.equals(owner.getIsInternal())) {
                return Optional.empty();
            }
            principal = owner.getUsername();
            // User-key authorities are ALWAYS derived live from the owner — never stored — so they
            // can't go stale. Null scope = row predating the scope column: default READ_WRITE.
            ApiKeyScope scope = key.getScope() != null ? key.getScope() : ApiKeyScope.READ_WRITE;
            if (scope == ApiKeyScope.CUSTOM) {
                // Type/scope invariant violation (only possible via direct DB manipulation):
                // refuse rather than honor a stored list on a self-service key.
                log.warn("Rejecting user API key {} with invariant-violating CUSTOM scope", key.getId());
                return Optional.empty();
            }
            List<String> live = authService.getPermissions(owner);
            authorities = (scope == ApiKeyScope.READ_ONLY) ? readOnlyView(live) : live;
        } else {
            // System key (CUSTOM): no owner to derive from — the stored list IS the authorities.
            principal = "system:" + key.getName();
            authorities = (key.getPermissions() != null) ? key.getPermissions() : List.of();
        }

        touchLastUsed(key);
        return Optional.of(new ApiKeyPrincipal(principal, authorities, key.getId()));
    }

    /**
     * The read-only slice of a permission set: expand the {@code super_admin} wildcard to the full
     * {@link Permission} universe, then keep only read actions. {@code super_admin} itself never
     * survives the filter — deliberately, so a read-only key can never pass a
     * {@code super_admin}-only gate.
     */
    private List<String> readOnlyView(List<String> livePermissions) {
        Stream<String> base = livePermissions.stream();
        if (livePermissions.contains("super_admin")) {
            base = Stream.concat(base, Permission.allReadPermissions().stream());
        }
        return base.filter(Permission::isReadAction).distinct().toList();
    }

    private void touchLastUsed(ApiKey key) {
        Instant now = Instant.now();
        if (key.getLastUsedAt() == null
                || key.getLastUsedAt().isBefore(now.minus(LAST_USED_THROTTLE_SECONDS, ChronoUnit.SECONDS))) {
            key.setLastUsedAt(now);
            apiKeyRepository.save(key);
        }
    }

    // ── Management (a user's own keys) ───────────────────────────────────────

    /** A user's active keys, newest first. */
    public List<ApiKey> listUserKeys(String userId) {
        return apiKeyRepository.findActiveByUserId(userId, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    /** All active system (service-account) keys, newest first. */
    public List<ApiKey> listSystemKeys() {
        return apiKeyRepository.findActiveByKeyType(ApiKeyType.SYSTEM, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    /**
     * Revoke every active key owned by a user — invoked when the user is deleted. Because deletion
     * is soft, revocation (which is permanent) ensures a later restore of the user does not
     * resurrect their credentials; it also keeps stored state honest and is a durable second barrier
     * behind the live owner check in {@link #authenticate}. Only user keys are touched (system keys
     * have no owner). Returns the number revoked.
     */
    public int revokeAllForUser(String userId) {
        List<ApiKey> active = apiKeyRepository.findActiveByUserId(userId);
        if (active.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        active.forEach(key -> key.setRevokedAt(now));
        apiKeyRepository.saveAll(active);
        return active.size();
    }

    /**
     * Revoke one of a user's own keys. Returns {@code false} if the key does not exist, is already
     * revoked, is not a user key, or is not owned by {@code userId} (so callers can 404 without
     * leaking other users' key ids).
     */
    public boolean revokeUserKey(String userId, String keyId) {
        ApiKey key = apiKeyRepository.findById(keyId).orElse(null);
        if (key == null
                || key.getKeyType() != ApiKeyType.USER
                || !userId.equals(key.getUserId())
                || key.getRevokedAt() != null) {
            return false;
        }
        key.setRevokedAt(Instant.now());
        apiKeyRepository.save(key);
        return true;
    }

    /**
     * Revoke a system key. Returns {@code false} if the key does not exist, is already revoked, or
     * is not a system key (so callers can 404 without probing user-key ids).
     */
    public boolean revokeSystemKey(String keyId) {
        ApiKey key = apiKeyRepository.findById(keyId).orElse(null);
        if (key == null
                || key.getKeyType() != ApiKeyType.SYSTEM
                || key.getRevokedAt() != null) {
            return false;
        }
        key.setRevokedAt(Instant.now());
        apiKeyRepository.save(key);
        return true;
    }

    /**
     * Rename and re-scope an active system key (full replace of name and permissions). System keys
     * have no owner, so their permissions are unrestricted (a null/empty list yields an inert key).
     * Returns empty if the key does not exist, is revoked, or is not a system key.
     */
    public Optional<ApiKey> updateSystemKey(String keyId, String name, List<String> permissions) {
        validateAssignablePermissions(permissions);
        ApiKey key = apiKeyRepository.findById(keyId).orElse(null);
        if (key == null
                || key.getKeyType() != ApiKeyType.SYSTEM
                || key.getRevokedAt() != null) {
            return Optional.empty();
        }
        key.setName(name);
        key.setPermissions(permissions != null ? new ArrayList<>(permissions) : new ArrayList<>());
        return Optional.of(apiKeyRepository.save(key));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Normalize and validate a user-key scope: {@code null} → the {@code READ_WRITE} default;
     * {@code CUSTOM} → rejected (self-service keys never carry a stored permission list).
     */
    /**
     * Reject any permission string a system key cannot meaningfully carry: everything must be either
     * the {@code super_admin} wildcard or a permission known to the {@link Permission} registry. A
     * {@code null}/empty list is fine (an inert key). This turns a typo'd or stale permission into a
     * 400 at assignment time rather than a silently inert authority that never matches a gate.
     */
    private void validateAssignablePermissions(List<String> permissions) {
        if (permissions == null) {
            return;
        }
        List<String> unknown = permissions.stream()
                .filter(p -> !"super_admin".equals(p) && Permission.fromString(p) == null)
                .distinct()
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown permission(s): " + String.join(", ", unknown));
        }
    }

    private ApiKeyScope requireUserScope(ApiKeyScope scope) {
        if (scope == null) {
            return ApiKeyScope.READ_WRITE;
        }
        if (scope == ApiKeyScope.CUSTOM) {
            throw new IllegalArgumentException("User API keys cannot use the CUSTOM scope");
        }
        return scope;
    }

    private String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] h = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Non-secret hint: the prefix plus the first few characters of the random part. */
    private String hint(String secret) {
        int end = Math.min(secret.length(), KEY_PREFIX.length() + HINT_RANDOM_CHARS);
        return secret.substring(0, end);
    }

    // ── Result types ─────────────────────────────────────────────────────────

    /** A freshly minted key: the persisted entity plus the one-time plaintext secret. */
    public record GeneratedApiKey(ApiKey apiKey, String secret) {}

    /** The outcome of authenticating a key: who the request acts as, and which key was used. */
    public record ApiKeyPrincipal(String principal, List<String> authorities, String keyId) {}
}
