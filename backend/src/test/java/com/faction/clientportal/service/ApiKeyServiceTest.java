package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.ApiKey;
import com.faction.clientportal.model.ApiKeyScope;
import com.faction.clientportal.model.ApiKeyType;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.ApiKeyRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ApiKeyServiceTest extends TestContainersConfig {

    @Autowired private ApiKeyService apiKeyService;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserService userService;
    @Autowired private PasswordEncoder passwordEncoder;

    // The test role deliberately mixes read and mutating actions so READ_ONLY filtering is provable.
    private static final List<String> ROLE_PERMS =
            List.of("assessments:read:team", "apikeys:read:self", "vulnerabilities:edit:assessment");

    private Role role;
    private User user;

    @BeforeEach
    void setUp() {
        // Clean up only what this class creates — other test classes share this context's DB
        // (notably the bootstrap-seeded admin/pentest users, which later classes log in with).
        apiKeyRepository.deleteAll();
        List.of("apikey-svc-pentester", "apikey-svc-portal", "apikey-svc-other")
                .forEach(username -> userRepository.findByUsername(username).ifPresent(userRepository::delete));
        roleRepository.findByName("ApiKeySvcRole").ifPresent(roleRepository::delete);

        role = roleRepository.save(Role.builder()
                .name("ApiKeySvcRole")
                .permissions(ROLE_PERMS)
                .build());

        user = userRepository.save(User.builder()
                .username("apikey-svc-pentester")
                .password(passwordEncoder.encode("pw"))
                .roleIds(List.of(role.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build());
    }

    private User saveUser(String username, boolean internal) {
        return userRepository.save(User.builder()
                .username(username)
                .password(passwordEncoder.encode("pw"))
                .roleIds(List.of(role.getId()))
                .isInternal(internal)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build());
    }

    // ── Generation ───────────────────────────────────────────────────────────

    @Test
    void createUserKey_returnsPrefixedSecret_andStoresOnlyHash() {
        ApiKeyService.GeneratedApiKey generated = apiKeyService.createUserKey(user.getId(), "CI pipeline");

        assertThat(generated.secret()).startsWith("sk_fac_");

        ApiKey stored = apiKeyRepository.findById(generated.apiKey().getId()).orElseThrow();
        // The plaintext is never persisted — only its hash.
        assertThat(stored.getTokenHash()).isNotBlank();
        assertThat(stored.getTokenHash()).isNotEqualTo(generated.secret());
        assertThat(stored.getTokenHash()).doesNotContain(generated.secret());
        // Hint is the prefix + first 6 random chars, and is a safe fragment of the real key.
        assertThat(stored.getHint()).startsWith("sk_fac_").hasSize("sk_fac_".length() + 6);
        assertThat(generated.secret()).startsWith(stored.getHint());
        assertThat(stored.getKeyType()).isEqualTo(ApiKeyType.USER);
        // Default scope is READ_WRITE, and user keys never store permissions.
        assertThat(stored.getScope()).isEqualTo(ApiKeyScope.READ_WRITE);
        assertThat(stored.getPermissions()).isEmpty();
    }

    @Test
    void createUserKey_readOnlyScope_isStored() {
        ApiKeyService.GeneratedApiKey generated =
                apiKeyService.createUserKey(user.getId(), "ro", ApiKeyScope.READ_ONLY);

        assertThat(apiKeyRepository.findById(generated.apiKey().getId()).orElseThrow().getScope())
                .isEqualTo(ApiKeyScope.READ_ONLY);
    }

    @Test
    void createUserKey_customScope_isRejected() {
        assertThatThrownBy(() -> apiKeyService.createUserKey(user.getId(), "nope", ApiKeyScope.CUSTOM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createSystemKey_isCustomScoped_andStoresPermissions() {
        ApiKeyService.GeneratedApiKey generated =
                apiKeyService.createSystemKey("svc", List.of("assessments:read:all"));

        ApiKey stored = apiKeyRepository.findById(generated.apiKey().getId()).orElseThrow();
        assertThat(stored.getScope()).isEqualTo(ApiKeyScope.CUSTOM);
        assertThat(stored.getPermissions()).containsExactly("assessments:read:all");
    }

    @Test
    void createSystemKey_allowsSuperAdminWildcard() {
        ApiKeyService.GeneratedApiKey generated =
                apiKeyService.createSystemKey("svc", List.of("super_admin"));

        assertThat(apiKeyRepository.findById(generated.apiKey().getId()).orElseThrow().getPermissions())
                .containsExactly("super_admin");
    }

    @Test
    void createSystemKey_withUnknownPermission_isRejected() {
        assertThatThrownBy(() ->
                apiKeyService.createSystemKey("svc", List.of("assessments:read:all", "bogus:permission")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bogus:permission");

        assertThat(apiKeyRepository.count()).isZero();
    }

    @Test
    void updateSystemKey_withUnknownPermission_isRejected() {
        ApiKey key = apiKeyService.createSystemKey("svc", List.of("assessments:read:all")).apiKey();

        assertThatThrownBy(() ->
                apiKeyService.updateSystemKey(key.getId(), "svc", List.of("nope:nope:nope")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope:nope:nope");

        // The original permissions are untouched — validation runs before any mutation.
        assertThat(apiKeyRepository.findById(key.getId()).orElseThrow().getPermissions())
                .containsExactly("assessments:read:all");
    }

    // ── Authentication: READ_WRITE (live inheritance) ────────────────────────

    @Test
    void authenticate_validUserKey_resolvesOwnerLivePermissions() {
        ApiKeyService.GeneratedApiKey generated = apiKeyService.createUserKey(user.getId(), "k");

        Optional<ApiKeyService.ApiKeyPrincipal> result = apiKeyService.authenticate(generated.secret());

        assertThat(result).isPresent();
        assertThat(result.get().principal()).isEqualTo("apikey-svc-pentester");
        assertThat(result.get().authorities()).containsExactlyInAnyOrderElementsOf(ROLE_PERMS);
        assertThat(result.get().keyId()).isEqualTo(generated.apiKey().getId());
    }

    @Test
    void authenticate_reflectsLivePermissionChanges_noStaleness() {
        ApiKeyService.GeneratedApiKey generated = apiKeyService.createUserKey(user.getId(), "k");

        role.setPermissions(List.of("super_admin"));
        roleRepository.save(role);

        Optional<ApiKeyService.ApiKeyPrincipal> result = apiKeyService.authenticate(generated.secret());
        assertThat(result).isPresent();
        assertThat(result.get().authorities()).containsExactly("super_admin");
    }

    // ── Authentication: READ_ONLY (live, filtered) ───────────────────────────

    @Test
    void authenticate_readOnlyKey_filtersToReadActions() {
        ApiKeyService.GeneratedApiKey generated =
                apiKeyService.createUserKey(user.getId(), "ro", ApiKeyScope.READ_ONLY);

        Optional<ApiKeyService.ApiKeyPrincipal> result = apiKeyService.authenticate(generated.secret());

        assertThat(result).isPresent();
        // The mutating permission (vulnerabilities:edit:assessment) is filtered out.
        assertThat(result.get().authorities())
                .containsExactlyInAnyOrder("assessments:read:team", "apikeys:read:self");
    }

    @Test
    void authenticate_readOnlyKey_tracksOwnerChangesBothDirections() {
        ApiKeyService.GeneratedApiKey generated =
                apiKeyService.createUserKey(user.getId(), "ro", ApiKeyScope.READ_ONLY);

        // Owner's role changes: loses the old read perms, gains a new read perm and a new write perm.
        role.setPermissions(List.of("organizations:read:all", "organizations:edit:all"));
        roleRepository.save(role);

        Optional<ApiKeyService.ApiKeyPrincipal> result = apiKeyService.authenticate(generated.secret());
        assertThat(result).isPresent();
        // Gained the new read permission (never snapshotted), lost the old ones, write still filtered.
        assertThat(result.get().authorities()).containsExactly("organizations:read:all");
    }

    @Test
    void authenticate_readOnlyKey_superAdminOwner_expandsToReadUniverse() {
        role.setPermissions(List.of("super_admin"));
        roleRepository.save(role);
        ApiKeyService.GeneratedApiKey generated =
                apiKeyService.createUserKey(user.getId(), "admin-ro", ApiKeyScope.READ_ONLY);

        Optional<ApiKeyService.ApiKeyPrincipal> result = apiKeyService.authenticate(generated.secret());

        assertThat(result).isPresent();
        // The super_admin wildcard expands to every read permission the enum knows...
        assertThat(result.get().authorities())
                .containsExactlyInAnyOrderElementsOf(Permission.allReadPermissions());
        // ...but never the wildcard itself, and nothing mutating.
        assertThat(result.get().authorities()).doesNotContain("super_admin");
        assertThat(result.get().authorities()).allMatch(Permission::isReadAction);
    }

    // ── Authentication: CUSTOM (system keys) ─────────────────────────────────

    @Test
    void authenticate_systemKey_withoutPermissions_isInert() {
        ApiKeyService.GeneratedApiKey generated = apiKeyService.createSystemKey("nightly-sync");

        Optional<ApiKeyService.ApiKeyPrincipal> result = apiKeyService.authenticate(generated.secret());
        assertThat(result).isPresent();
        assertThat(result.get().principal()).isEqualTo("system:nightly-sync");
        assertThat(result.get().authorities()).isEmpty();
    }

    @Test
    void authenticate_systemKey_withAssignedPermissions_usesThem() {
        ApiKeyService.GeneratedApiKey generated =
                apiKeyService.createSystemKey("siem-export",
                        List.of("vulnerabilities:read:all", "assessments:read:all"));

        Optional<ApiKeyService.ApiKeyPrincipal> result = apiKeyService.authenticate(generated.secret());
        assertThat(result).isPresent();
        assertThat(result.get().authorities())
                .containsExactlyInAnyOrder("vulnerabilities:read:all", "assessments:read:all");
    }

    // ── Authentication: rejection paths ──────────────────────────────────────

    @Test
    void authenticate_revokedKey_returnsEmpty() {
        ApiKeyService.GeneratedApiKey generated = apiKeyService.createUserKey(user.getId(), "k");
        apiKeyService.revokeUserKey(user.getId(), generated.apiKey().getId());

        assertThat(apiKeyService.authenticate(generated.secret())).isEmpty();
    }

    @Test
    void authenticate_expiredKey_returnsEmpty() {
        ApiKeyService.GeneratedApiKey generated = apiKeyService.createUserKey(user.getId(), "k");
        ApiKey key = apiKeyRepository.findById(generated.apiKey().getId()).orElseThrow();
        key.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        apiKeyRepository.save(key);

        assertThat(apiKeyService.authenticate(generated.secret())).isEmpty();
    }

    @Test
    void authenticate_deletedOwner_returnsEmpty() {
        ApiKeyService.GeneratedApiKey generated = apiKeyService.createUserKey(user.getId(), "k");
        user.setDeletedAt(LocalDateTime.now());
        userRepository.save(user);

        assertThat(apiKeyService.authenticate(generated.secret())).isEmpty();
    }

    @Test
    void authenticate_disabledOwner_returnsEmpty() {
        ApiKeyService.GeneratedApiKey generated = apiKeyService.createUserKey(user.getId(), "k");
        user.setDisabledAt(LocalDateTime.now());
        userRepository.save(user);

        assertThat(apiKeyService.authenticate(generated.secret())).isEmpty();
    }

    @Test
    void authenticate_externalOwner_returnsEmpty() {
        User external = saveUser("apikey-svc-portal", false);
        ApiKeyService.GeneratedApiKey generated = apiKeyService.createUserKey(external.getId(), "k");

        assertThat(apiKeyService.authenticate(generated.secret())).isEmpty();
    }

    @Test
    void authenticate_userKeyWithInvariantViolatingCustomScope_returnsEmpty() {
        // Only reachable by direct DB manipulation — the service never writes CUSTOM on a user key.
        ApiKeyService.GeneratedApiKey generated = apiKeyService.createUserKey(user.getId(), "k");
        ApiKey key = apiKeyRepository.findById(generated.apiKey().getId()).orElseThrow();
        key.setScope(ApiKeyScope.CUSTOM);
        key.setPermissions(List.of("super_admin"));
        apiKeyRepository.save(key);

        assertThat(apiKeyService.authenticate(generated.secret())).isEmpty();
    }

    @Test
    void authenticate_unknownOrNonPrefixedKey_returnsEmpty() {
        assertThat(apiKeyService.authenticate(null)).isEmpty();
        assertThat(apiKeyService.authenticate("not-an-api-key")).isEmpty();
        assertThat(apiKeyService.authenticate("sk_fac_totally-made-up")).isEmpty();
    }

    @Test
    void authenticate_updatesLastUsedAt() {
        ApiKeyService.GeneratedApiKey generated = apiKeyService.createUserKey(user.getId(), "k");
        assertThat(apiKeyRepository.findById(generated.apiKey().getId()).orElseThrow().getLastUsedAt()).isNull();

        apiKeyService.authenticate(generated.secret());

        assertThat(apiKeyRepository.findById(generated.apiKey().getId()).orElseThrow().getLastUsedAt()).isNotNull();
    }

    // ── Management: user keys ─────────────────────────────────────────────────

    @Test
    void revokeUserKey_onlyAffectsCallersOwnActiveUserKeys() {
        ApiKeyService.GeneratedApiKey mine = apiKeyService.createUserKey(user.getId(), "mine");
        User other = saveUser("apikey-svc-other", true);
        ApiKeyService.GeneratedApiKey theirs = apiKeyService.createUserKey(other.getId(), "theirs");
        ApiKeyService.GeneratedApiKey system = apiKeyService.createSystemKey("svc");

        assertThat(apiKeyService.revokeUserKey(user.getId(), theirs.apiKey().getId())).isFalse();
        assertThat(apiKeyService.revokeUserKey(user.getId(), system.apiKey().getId())).isFalse();
        assertThat(apiKeyService.revokeUserKey(user.getId(), mine.apiKey().getId())).isTrue();
        // Second revoke of the same key is a no-op.
        assertThat(apiKeyService.revokeUserKey(user.getId(), mine.apiKey().getId())).isFalse();
    }

    @Test
    void listUserKeys_returnsActiveKeysNewestFirst() {
        ApiKeyService.GeneratedApiKey first = apiKeyService.createUserKey(user.getId(), "first");
        apiKeyService.createUserKey(user.getId(), "second");
        apiKeyService.revokeUserKey(user.getId(), first.apiKey().getId());

        List<ApiKey> active = apiKeyService.listUserKeys(user.getId());

        assertThat(active).extracting(ApiKey::getName).containsExactly("second");
    }

    // ── Management: system keys ───────────────────────────────────────────────

    @Test
    void updateSystemKey_changesNameAndPermissions_unrestricted() {
        ApiKey key = apiKeyService.createSystemKey("svc").apiKey();

        Optional<ApiKey> updated =
                apiKeyService.updateSystemKey(key.getId(), "svc2", List.of("super_admin"));

        assertThat(updated).isPresent();
        assertThat(updated.get().getName()).isEqualTo("svc2");
        assertThat(updated.get().getPermissions()).containsExactly("super_admin");
    }

    @Test
    void updateSystemKey_onUserKey_returnsEmpty() {
        ApiKey userKey = apiKeyService.createUserKey(user.getId(), "u").apiKey();

        assertThat(apiKeyService.updateSystemKey(userKey.getId(), "x", null)).isEmpty();
    }

    @Test
    void revokeSystemKey_onlyAffectsActiveSystemKeys() {
        ApiKey system = apiKeyService.createSystemKey("svc").apiKey();
        ApiKey userKey = apiKeyService.createUserKey(user.getId(), "u").apiKey();

        assertThat(apiKeyService.revokeSystemKey(userKey.getId())).isFalse();
        assertThat(apiKeyService.revokeSystemKey(system.getId())).isTrue();
        assertThat(apiKeyService.revokeSystemKey(system.getId())).isFalse();
    }

    @Test
    void listSystemKeys_returnsActiveNewestFirst() {
        apiKeyService.createSystemKey("sys-first");
        ApiKey second = apiKeyService.createSystemKey("sys-second").apiKey();
        apiKeyService.createSystemKey("sys-third");
        apiKeyService.revokeSystemKey(second.getId());

        assertThat(apiKeyService.listSystemKeys()).extracting(ApiKey::getName)
                .containsExactly("sys-third", "sys-first");
    }

    // ── Owner lifecycle: delete revokes keys; disable only makes them inert ───

    @Test
    void revokeAllForUser_revokesOnlyThatUsersActiveKeys() {
        apiKeyService.createUserKey(user.getId(), "a");
        apiKeyService.createUserKey(user.getId(), "b");
        User other = saveUser("apikey-svc-other", true);
        ApiKey othersKey = apiKeyService.createUserKey(other.getId(), "theirs").apiKey();
        ApiKey systemKey = apiKeyService.createSystemKey("svc").apiKey();

        int revoked = apiKeyService.revokeAllForUser(user.getId());

        assertThat(revoked).isEqualTo(2);
        assertThat(apiKeyService.listUserKeys(user.getId())).isEmpty();
        // Other users' keys and system keys are untouched.
        assertThat(apiKeyRepository.findById(othersKey.getId()).orElseThrow().getRevokedAt()).isNull();
        assertThat(apiKeyRepository.findById(systemKey.getId()).orElseThrow().getRevokedAt()).isNull();
    }

    @Test
    void revokeAllForUser_revocationIsPermanent_survivesHealthyOwner() {
        ApiKeyService.GeneratedApiKey generated = apiKeyService.createUserKey(user.getId(), "k");

        apiKeyService.revokeAllForUser(user.getId());

        // The owner remains fully healthy (not deleted/disabled), yet the key stays dead because it
        // is revoked — the "restoring a soft-deleted user cannot resurrect credentials" guarantee.
        assertThat(apiKeyService.authenticate(generated.secret())).isEmpty();
        assertThat(apiKeyRepository.findById(generated.apiKey().getId()).orElseThrow().getRevokedAt())
                .isNotNull();
    }

    @Test
    void deleteUser_revokesTheUsersKeys() {
        ApiKeyService.GeneratedApiKey generated = apiKeyService.createUserKey(user.getId(), "k");

        userService.deleteUser(user.getId());

        assertThat(apiKeyRepository.findById(generated.apiKey().getId()).orElseThrow().getRevokedAt())
                .isNotNull();
    }

    @Test
    void disablingOwner_makesKeysInert_butDoesNotRevokeThem() {
        ApiKeyService.GeneratedApiKey generated = apiKeyService.createUserKey(user.getId(), "k");

        user.setDisabledAt(LocalDateTime.now());
        userRepository.save(user);

        // Inert while disabled (the live owner check)...
        assertThat(apiKeyService.authenticate(generated.secret())).isEmpty();
        // ...but NOT revoked — re-enabling the user restores the key (disable is reversible).
        assertThat(apiKeyRepository.findById(generated.apiKey().getId()).orElseThrow().getRevokedAt())
                .isNull();
    }

    // ── Name uniqueness (among active keys, per scope; case-insensitive) ──────

    @Test
    void createUserKey_duplicateActiveName_sameOwner_isRejected() {
        apiKeyService.createUserKey(user.getId(), "CI");

        assertThatThrownBy(() -> apiKeyService.createUserKey(user.getId(), "CI"))
                .isInstanceOf(IllegalArgumentException.class);
        // Case-insensitive and trimmed — "  ci  " collides with "CI".
        assertThatThrownBy(() -> apiKeyService.createUserKey(user.getId(), "  ci  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createUserKey_sameName_differentOwners_isAllowed() {
        User other = saveUser("apikey-svc-other", true);
        apiKeyService.createUserKey(user.getId(), "CI");

        // Different owner, same name is fine — their principals differ (by username).
        assertThat(apiKeyService.createUserKey(other.getId(), "CI").apiKey().getName()).isEqualTo("CI");
    }

    @Test
    void createUserKey_reuseNameAfterRevoke_isAllowed() {
        ApiKey first = apiKeyService.createUserKey(user.getId(), "CI").apiKey();
        apiKeyService.revokeUserKey(user.getId(), first.getId());

        assertThat(apiKeyService.createUserKey(user.getId(), "CI").apiKey().getName()).isEqualTo("CI");
    }

    @Test
    void createSystemKey_duplicateActiveName_isRejected() {
        apiKeyService.createSystemKey("svc-admin");

        assertThatThrownBy(() -> apiKeyService.createSystemKey("svc-admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createSystemKey_reuseNameAfterRevoke_isAllowed() {
        ApiKey first = apiKeyService.createSystemKey("svc-admin").apiKey();
        apiKeyService.revokeSystemKey(first.getId());

        assertThat(apiKeyService.createSystemKey("svc-admin").apiKey().getName()).isEqualTo("svc-admin");
    }
}
