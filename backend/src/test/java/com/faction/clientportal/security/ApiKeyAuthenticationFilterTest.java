package com.faction.clientportal.security;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.ApiKeyRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.ApiKeyService;
import com.faction.clientportal.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of {@link ApiKeyAuthenticationFilter} through the HTTP filter chain, using
 * keys minted directly via {@link ApiKeyService} (the management controller does not exist yet).
 * Exercised against an existing protected endpoint ({@code GET /api/v1/roles} requires
 * {@code super_admin}) and {@code GET /api/v1/auth/me} (echoes the resolved principal/authorities).
 *
 * <p>Note: this app's security returns 403 for unauthenticated, invalid, and forbidden requests
 * alike, so negative cases assert 403; the positive 200s and the {@code /me} echo prove the key
 * genuinely authenticated.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiKeyAuthenticationFilterTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApiKeyService apiKeyService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private User superAdminUser;
    private User limitedUser;

    @BeforeEach
    void setUp() {
        // Clean up only what this class creates — the context's DB (incl. bootstrap users) is
        // shared with other test classes.
        apiKeyRepository.deleteAll();
        List.of("apikey-filter-admin", "apikey-filter-limited")
                .forEach(username -> userRepository.findByUsername(username).ifPresent(userRepository::delete));
        List.of("ApiKeyFilterAdminRole", "ApiKeyFilterLimitedRole")
                .forEach(name -> roleRepository.findByName(name).ifPresent(roleRepository::delete));

        Role superAdminRole = roleRepository.save(Role.builder()
                .name("ApiKeyFilterAdminRole").permissions(List.of("super_admin")).build());
        Role limitedRole = roleRepository.save(Role.builder()
                .name("ApiKeyFilterLimitedRole").permissions(List.of("assessments:read:team")).build());

        superAdminUser = userRepository.save(user("apikey-filter-admin", superAdminRole.getId(), true));
        limitedUser = userRepository.save(user("apikey-filter-limited", limitedRole.getId(), true));
    }

    private User user(String username, String roleId, boolean internal) {
        return User.builder()
                .username(username)
                .password(passwordEncoder.encode("pw"))
                .roleIds(List.of(roleId))
                .isInternal(internal)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
    }

    private static String bearer(String key) {
        return "Bearer " + key;
    }

    @Test
    void superAdminUserKey_authenticatesAndAuthorizes() throws Exception {
        String secret = apiKeyService.createUserKey(superAdminUser.getId(), "ci").secret();

        mockMvc.perform(get("/api/v1/roles").header("Authorization", bearer(secret)))
                .andExpect(status().isOk());
    }

    @Test
    void userKey_populatesPrincipalAndResolvedAuthorities() throws Exception {
        String secret = apiKeyService.createUserKey(limitedUser.getId(), "laptop").secret();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(secret)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("apikey-filter-limited"))
                .andExpect(jsonPath("$.authorities").value("assessments:read:team"));
    }

    @Test
    void userKeyWithoutRequiredPermission_isForbidden() throws Exception {
        String secret = apiKeyService.createUserKey(limitedUser.getId(), "laptop").secret();

        mockMvc.perform(get("/api/v1/roles").header("Authorization", bearer(secret)))
                .andExpect(status().isForbidden());
    }

    @Test
    void revokedKey_isRejected() throws Exception {
        ApiKeyService.GeneratedApiKey generated = apiKeyService.createUserKey(superAdminUser.getId(), "ci");
        // Works before revocation...
        mockMvc.perform(get("/api/v1/roles").header("Authorization", bearer(generated.secret())))
                .andExpect(status().isOk());

        apiKeyService.revokeUserKey(superAdminUser.getId(), generated.apiKey().getId());

        // ...and is rejected after.
        mockMvc.perform(get("/api/v1/roles").header("Authorization", bearer(generated.secret())))
                .andExpect(status().isForbidden());
    }

    @Test
    void systemKeyWithoutPermissions_isForbidden() throws Exception {
        String secret = apiKeyService.createSystemKey("svc").secret();

        mockMvc.perform(get("/api/v1/roles").header("Authorization", bearer(secret)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownApiKey_isRejected() throws Exception {
        mockMvc.perform(get("/api/v1/roles").header("Authorization", bearer("sk_fac_totally-made-up")))
                .andExpect(status().isForbidden());
    }

    @Test
    void jwtAuthenticationStillWorks_alongsideApiKeyFilter() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin")));

        mockMvc.perform(get("/api/v1/roles").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
