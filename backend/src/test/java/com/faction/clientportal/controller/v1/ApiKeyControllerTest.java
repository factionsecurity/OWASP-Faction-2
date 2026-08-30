package com.faction.clientportal.controller.v1;

import com.faction.clientportal.edition.EnterpriseOnly;
import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.ApiKey;
import com.faction.clientportal.model.ApiKeyScope;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.ApiKeyRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.ApiKeyService;
import com.faction.clientportal.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level coverage of {@link ApiKeyController} — permission gates, the internal-user block, the
 * one-time-secret contract, the scope model (READ_WRITE/READ_ONLY for user keys, CUSTOM for system
 * keys), and the super_admin-only system-key write boundary. Callers authenticate with JWTs minted
 * via {@link JwtService} carrying the exact authorities under test; keys whose id or secret is
 * needed are minted directly via {@link ApiKeyService}.
 *
 * <p>This app returns 403 for unauthenticated and forbidden alike, so negative auth cases assert 403.
 * Setup is non-destructive (targeted cleanup + unique names) so it doesn't disturb the shared context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiKeyControllerTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ApiKeyService apiKeyService;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    // A functional read permission the self-manager holds (survives READ_ONLY filtering).
    private static final String READ_PERM = "assessments:read:team";
    // A permission assigned to system keys in tests (never held by the self-manager).
    private static final String SYSTEM_PERM = "assessments:read:all";

    private User admin;          // super_admin
    private User selfManager;    // apikeys:*:self + READ_PERM, internal
    private User noPerms;        // internal, no apikeys perms
    private User externalSelf;   // apikeys:create:self but external

    @BeforeEach
    void setUp() {
        apiKeyRepository.deleteAll();
        List.of("apikey-ctl-admin", "apikey-ctl-self", "apikey-ctl-noperm", "apikey-ctl-external")
                .forEach(u -> userRepository.findByUsername(u).ifPresent(userRepository::delete));
        List.of("ApiKeyCtlAdmin", "ApiKeyCtlSelf", "ApiKeyCtlNoPerm", "ApiKeyCtlExternal")
                .forEach(r -> roleRepository.findByName(r).ifPresent(roleRepository::delete));

        Role adminRole = saveRole("ApiKeyCtlAdmin", List.of("super_admin"));
        Role selfRole = saveRole("ApiKeyCtlSelf", List.of(
                "apikeys:create:self", "apikeys:read:self", "apikeys:delete:self", READ_PERM));
        Role noPermRole = saveRole("ApiKeyCtlNoPerm", List.of("assessments:read:team"));
        Role externalRole = saveRole("ApiKeyCtlExternal", List.of("apikeys:create:self"));

        admin = saveUser("apikey-ctl-admin", adminRole, true);
        selfManager = saveUser("apikey-ctl-self", selfRole, true);
        noPerms = saveUser("apikey-ctl-noperm", noPermRole, true);
        externalSelf = saveUser("apikey-ctl-external", externalRole, false);
    }

    private Role saveRole(String name, List<String> perms) {
        return roleRepository.save(Role.builder().name(name).permissions(perms).build());
    }

    private User saveUser(String username, Role role, boolean internal) {
        return userRepository.save(User.builder()
                .username(username)
                .password(passwordEncoder.encode("pw"))
                .roleIds(List.of(role.getId()))
                .isInternal(internal)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build());
    }

    /** A JWT carrying exactly the given authorities for the given user. */
    private String tokenFor(User user, String... authorities) {
        return "Bearer " + jwtService.generateToken(user.getUsername(),
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    // ── User keys ─────────────────────────────────────────────────────────────

    @Test
    void createUserKey_withPermission_returnsPlaintextOnce() throws Exception {
        mockMvc.perform(post("/api/v1/api-keys")
                        .header("Authorization", tokenFor(selfManager, "apikeys:create:self"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "ci"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.key").value(org.hamcrest.Matchers.startsWith("sk_fac_")))
                .andExpect(jsonPath("$.data.apiKey.name").value("ci"))
                .andExpect(jsonPath("$.data.apiKey.scope").value("READ_WRITE")) // the default
                .andExpect(jsonPath("$.data.apiKey.hint").value(org.hamcrest.Matchers.startsWith("sk_fac_")))
                // The metadata never leaks the hash or a re-usable secret.
                .andExpect(jsonPath("$.data.apiKey.tokenHash").doesNotExist());
    }

    @Test
    void createUserKey_readOnlyScope_isAcceptedAndEchoed() throws Exception {
        mockMvc.perform(post("/api/v1/api-keys")
                        .header("Authorization", tokenFor(selfManager, "apikeys:create:self"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "ro", "scope", "READ_ONLY"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.apiKey.scope").value("READ_ONLY"));
    }

    @Test
    void createUserKey_customScope_isBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/api-keys")
                        .header("Authorization", tokenFor(selfManager, "apikeys:create:self"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "nope", "scope", "CUSTOM"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createUserKey_duplicateActiveName_isBadRequest() throws Exception {
        apiKeyService.createUserKey(selfManager.getId(), "dup");

        mockMvc.perform(post("/api/v1/api-keys")
                        .header("Authorization", tokenFor(selfManager, "apikeys:create:self"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "dup"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createUserKey_withoutPermission_isForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/api-keys")
                        .header("Authorization", tokenFor(noPerms, "assessments:read:team"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "nope"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void createUserKey_asExternalUser_isForbiddenEvenWithPermission() throws Exception {
        mockMvc.perform(post("/api/v1/api-keys")
                        .header("Authorization", tokenFor(externalSelf, "apikeys:create:self"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "portal"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void listUserKeys_returnsOwnKeysWithoutSecret() throws Exception {
        apiKeyService.createUserKey(selfManager.getId(), "one");

        mockMvc.perform(get("/api/v1/api-keys")
                        .header("Authorization", tokenFor(selfManager, "apikeys:read:self")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("one"))
                .andExpect(jsonPath("$.data[0].hint").exists())
                .andExpect(jsonPath("$.data[0].tokenHash").doesNotExist())
                .andExpect(jsonPath("$.data[0].key").doesNotExist());
    }

    @Test
    void revokeUserKey_ownKey_thenUnknown() throws Exception {
        ApiKey key = apiKeyService.createUserKey(selfManager.getId(), "temp").apiKey();

        mockMvc.perform(delete("/api/v1/api-keys/" + key.getId())
                        .header("Authorization", tokenFor(selfManager, "apikeys:delete:self")))
                .andExpect(status().isOk());

        // Second revoke (already revoked) → 404.
        mockMvc.perform(delete("/api/v1/api-keys/" + key.getId())
                        .header("Authorization", tokenFor(selfManager, "apikeys:delete:self")))
                .andExpect(status().isNotFound());
    }

    @Test
    void revokeUserKey_notOwned_isNotFound() throws Exception {
        // A key owned by someone else must not be revocable, and must 404 (no id probing).
        ApiKey othersKey = apiKeyService.createUserKey(admin.getId(), "admins").apiKey();

        mockMvc.perform(delete("/api/v1/api-keys/" + othersKey.getId())
                        .header("Authorization", tokenFor(selfManager, "apikeys:delete:self")))
                .andExpect(status().isNotFound());
    }

    // ── READ_ONLY behavior through the full HTTP stack ────────────────────────

    @Test
    void readOnlyKey_canRead_butCannotWrite() throws Exception {
        // The self-manager's role mixes read and write apikeys perms; a READ_ONLY key keeps only
        // the reads. Exercised against this controller's own endpoints.
        String secret = apiKeyService
                .createUserKey(selfManager.getId(), "ro", ApiKeyScope.READ_ONLY).secret();

        // apikeys:read:self survives the filter → list works.
        mockMvc.perform(get("/api/v1/api-keys")
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk());

        // apikeys:create:self is a mutating action → filtered out → create is forbidden,
        // even though the OWNER holds the permission.
        mockMvc.perform(post("/api/v1/api-keys")
                        .header("Authorization", "Bearer " + secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "sneaky"))))
                .andExpect(status().isForbidden());
    }

    @Test

    @EnterpriseOnly
    void readOnlyKey_ofSuperAdmin_readsAdminResources_butCannotWrite() throws Exception {
        String secret = apiKeyService
                .createUserKey(admin.getId(), "admin-ro", ApiKeyScope.READ_ONLY).secret();

        // The super_admin wildcard expands to the read universe, so the key can read admin
        // resources whose gates accept a scoped read permission (apikeys:read:self, roles:read:all).
        mockMvc.perform(get("/api/v1/api-keys")
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/roles")
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk());

        // ...but it never carries the super_admin wildcard itself and mutating actions are filtered,
        // so a super_admin-only write (create a role) is still refused.
        mockMvc.perform(post("/api/v1/roles")
                        .header("Authorization", "Bearer " + secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "ro-should-not-create", "permissions", List.of()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void readOnlyKey_ofSuperAdmin_canReadPeerReviews() throws Exception {
        // Peer-review reads are gated on peerreview:read:*, which the read-universe expansion carries.
        String secret = apiKeyService
                .createUserKey(admin.getId(), "admin-ro-pr", ApiKeyScope.READ_ONLY).secret();

        mockMvc.perform(get("/api/v1/peer-reviews/queue")
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk());
    }

    // GET /auth/me must report the credential's EFFECTIVE authorities (from the Authentication),
    // not the owner's full permission set — so introspection matches enforcement for scoped keys.

    @Test
    void authMe_readOnlyKey_reportsOnlyReadAuthorities() throws Exception {
        // selfManager's role mixes reads (apikeys:read:self, assessments:read:team) and mutating
        // actions (apikeys:create:self, apikeys:delete:self).
        String secret = apiKeyService
                .createUserKey(selfManager.getId(), "ro", ApiKeyScope.READ_ONLY).secret();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("apikey-ctl-self"))
                .andExpect(jsonPath("$.authorities", org.hamcrest.Matchers.containsInAnyOrder(
                        "apikeys:read:self", "assessments:read:team")))
                .andExpect(jsonPath("$.authorities",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("apikeys:create:self"))))
                .andExpect(jsonPath("$.authorities",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("apikeys:delete:self"))));
    }

    @Test
    void authMe_readWriteKey_reportsMutatingAuthoritiesToo() throws Exception {
        String secret = apiKeyService
                .createUserKey(selfManager.getId(), "rw", ApiKeyScope.READ_WRITE).secret();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorities",
                        org.hamcrest.Matchers.hasItem("apikeys:read:self")))
                .andExpect(jsonPath("$.authorities",
                        org.hamcrest.Matchers.hasItem("apikeys:create:self")));
    }

    @Test
    void authMe_systemKey_returnsSystemPrincipal_withoutIdOrServerError() throws Exception {
        // A system-key principal ("system:<name>") has no user row; /auth/me must still introspect
        // rather than 500, and "id" is omitted entirely (not null).
        String secret = apiKeyService
                .createSystemKey("introspect", List.of("assessments:read:all")).secret();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("system:introspect"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.authorities",
                        org.hamcrest.Matchers.hasItem("assessments:read:all")));
    }

    @Test
    void authMe_userKey_includesUserId() throws Exception {
        String secret = apiKeyService.createUserKey(selfManager.getId(), "k").secret();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(selfManager.getId()));
    }

    // ── System keys ─────────────────────────────────────────────────────────

    @Test
    void createSystemKey_asSuperAdmin_withPermissions() throws Exception {
        mockMvc.perform(post("/api/v1/api-keys/system")
                        .header("Authorization", tokenFor(admin, "super_admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "nightly", "permissions", List.of(SYSTEM_PERM)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.key").value(org.hamcrest.Matchers.startsWith("sk_fac_")))
                .andExpect(jsonPath("$.data.apiKey.keyType").value("SYSTEM"))
                .andExpect(jsonPath("$.data.apiKey.scope").value("CUSTOM"))
                .andExpect(jsonPath("$.data.apiKey.permissions[0]").value(SYSTEM_PERM));
    }

    @Test
    void createSystemKey_withOnlySelfPermission_isForbidden() throws Exception {
        // apikeys:create:self does NOT authorize the system surface.
        mockMvc.perform(post("/api/v1/api-keys/system")
                        .header("Authorization", tokenFor(selfManager, "apikeys:create:self"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "svc"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void listAndUpdateAndRevokeSystemKey_asSuperAdmin() throws Exception {
        ApiKey key = apiKeyService.createSystemKey("svc", List.of(SYSTEM_PERM)).apiKey();

        mockMvc.perform(get("/api/v1/api-keys/system")
                        .header("Authorization", tokenFor(admin, "super_admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].keyType").value("SYSTEM"))
                .andExpect(jsonPath("$.data[0].scope").value("CUSTOM"))
                .andExpect(jsonPath("$.data[0].tokenHash").doesNotExist());

        // System keys are CUSTOM: any permissions may be assigned (super_admin-gated).
        mockMvc.perform(put("/api/v1/api-keys/system/" + key.getId())
                        .header("Authorization", tokenFor(admin, "super_admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "svc2", "permissions", List.of("super_admin")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("svc2"))
                .andExpect(jsonPath("$.data.permissions[0]").value("super_admin"));

        mockMvc.perform(delete("/api/v1/api-keys/system/" + key.getId())
                        .header("Authorization", tokenFor(admin, "super_admin")))
                .andExpect(status().isOk());
    }

    @Test
    void updateSystemKey_unknown_isNotFound() throws Exception {
        mockMvc.perform(put("/api/v1/api-keys/system/does-not-exist")
                        .header("Authorization", tokenFor(admin, "super_admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "x"))))
                .andExpect(status().isNotFound());
    }

    // The escalation guard: minting/re-scoping a system key is super_admin-only, so no scoped
    // apikeys authority — not even the strongest delegable pair — can create a system key (and thus
    // cannot mint an arbitrary-permission credential). Only the non-escalating verbs are delegable.

    @Test
    void createSystemKey_asNonAdmin_isForbiddenEvenWithSystemReadAndDelete() throws Exception {
        mockMvc.perform(post("/api/v1/api-keys/system")
                        .header("Authorization",
                                tokenFor(selfManager, "apikeys:read:system", "apikeys:delete:system"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "svc", "permissions", List.of("super_admin")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateSystemKey_asNonAdmin_isForbidden() throws Exception {
        ApiKey key = apiKeyService.createSystemKey("svc").apiKey();

        mockMvc.perform(put("/api/v1/api-keys/system/" + key.getId())
                        .header("Authorization",
                                tokenFor(selfManager, "apikeys:read:system", "apikeys:delete:system"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "svc", "permissions", List.of("super_admin")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void listSystemKeys_isDelegableViaReadSystem() throws Exception {
        apiKeyService.createSystemKey("svc");

        mockMvc.perform(get("/api/v1/api-keys/system")
                        .header("Authorization", tokenFor(selfManager, "apikeys:read:system")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].keyType").value("SYSTEM"));
    }

    @Test
    void revokeSystemKey_isDelegableViaDeleteSystem() throws Exception {
        ApiKey key = apiKeyService.createSystemKey("svc").apiKey();

        mockMvc.perform(delete("/api/v1/api-keys/system/" + key.getId())
                        .header("Authorization", tokenFor(selfManager, "apikeys:delete:system")))
                .andExpect(status().isOk());
    }

    // ── System keys as CALLERS (key-authenticated management) ─────────────────
    // The /system endpoints require only "not an external user", so a system key granted a
    // delegable :system verb is a legitimate caller — the automation those verbs exist for.

    @Test
    void systemKey_withReadSystem_canListSystemKeys() throws Exception {
        String secret = apiKeyService
                .createSystemKey("auditor", List.of("apikeys:read:system")).secret();
        apiKeyService.createSystemKey("inventory-target");

        mockMvc.perform(get("/api/v1/api-keys/system")
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void systemKey_withDeleteSystem_canRevokeSystemKeys() throws Exception {
        String secret = apiKeyService
                .createSystemKey("soar", List.of("apikeys:delete:system")).secret();
        ApiKey compromised = apiKeyService.createSystemKey("compromised").apiKey();

        mockMvc.perform(delete("/api/v1/api-keys/system/" + compromised.getId())
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk());
    }

    @Test
    void superAdminSystemKey_canCreateSystemKeys() throws Exception {
        // Accepted equivalent-power: a super_admin system key can already do everything through
        // every OTHER controller (none of which check the caller's identity), so blocking it only
        // here would be incoherent theater, not defense.
        String secret = apiKeyService
                .createSystemKey("automation", List.of("super_admin")).secret();

        mockMvc.perform(post("/api/v1/api-keys/system")
                        .header("Authorization", "Bearer " + secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "minted-by-key"))))
                .andExpect(status().isCreated());
    }

    @Test
    void externalUser_forbiddenOnSystemEndpoints_evenWithPermission() throws Exception {
        // The not-external check is the defense-in-depth that survives the relaxation.
        mockMvc.perform(get("/api/v1/api-keys/system")
                        .header("Authorization", tokenFor(externalSelf, "apikeys:read:system")))
                .andExpect(status().isForbidden());
    }

    @Test
    void systemKey_cannotUseUserKeyEndpoints() throws Exception {
        // User-key endpoints need a real internal owner; a system key has nothing to own there,
        // regardless of what authorities it carries.
        String secret = apiKeyService
                .createSystemKey("svc", List.of("apikeys:read:self", "apikeys:create:self")).secret();

        mockMvc.perform(get("/api/v1/api-keys")
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/api-keys")
                        .header("Authorization", "Bearer " + secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "orphan"))))
                .andExpect(status().isForbidden());
    }
}
