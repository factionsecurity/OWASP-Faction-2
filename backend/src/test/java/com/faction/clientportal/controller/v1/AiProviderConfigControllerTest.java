package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.AiProviderConfig;
import com.faction.clientportal.model.AiProviderType;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.AiProviderConfigRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiProviderConfigControllerTest extends TestContainersConfig {

    private static final String MASKED = "••••••••";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AiProviderConfigRepository aiProviderConfigRepository;
    @Autowired private com.faction.clientportal.repository.WebSearchConfigRepository webSearchConfigRepository;
    @Autowired private com.faction.clientportal.repository.AiAnonymizationConfigRepository aiAnonymizationConfigRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String adminToken;
    private String readerToken;
    private String basicToken;

    @BeforeEach
    void setUp() {
        webSearchConfigRepository.deleteAll();
        aiAnonymizationConfigRepository.deleteAll();
        aiProviderConfigRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role adminRole = roleRepository.save(Role.builder()
                .name("SuperAdmin")
                .permissions(List.of("super_admin"))
                .build());

        Role readerRole = roleRepository.save(Role.builder()
                .name("AiReader")
                .permissions(List.of("ai:config:read"))
                .build());

        Role basicRole = roleRepository.save(Role.builder()
                .name("Basic")
                .permissions(List.of("vulnerabilities:read:all"))
                .build());

        User admin = userRepository.save(user("ai-config-admin", adminRole));
        User reader = userRepository.save(user("ai-config-reader", readerRole));
        User basic = userRepository.save(user("ai-config-basic", basicRole));

        adminToken = jwtService.generateToken(admin.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin")));
        readerToken = jwtService.generateToken(reader.getUsername(),
                List.of(new SimpleGrantedAuthority("ai:config:read")));
        basicToken = jwtService.generateToken(basic.getUsername(),
                List.of(new SimpleGrantedAuthority("vulnerabilities:read:all")));
    }

    private User user(String username, Role role) {
        return User.builder()
                .username(username)
                .firstName("Test")
                .lastName("User")
                .email(username + "@test.com")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(role.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
    }

    @Test
    void getProviders_returnsEmptyListInitially() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ai-config")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void getProviders_allowsReadPermission() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ai-config")
                        .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isOk());
    }

    @Test
    void getProviders_forbiddenWithoutPermission() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ai-config")
                        .header("Authorization", "Bearer " + basicToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void createProvider_persistsAndMasksApiKey() throws Exception {
        Map<String, Object> request = Map.of(
                "name", "OpenAI Prod",
                "providerType", "OPENAI",
                "apiKey", "sk-test-secret-key",
                "models", List.of("gpt-4o", "gpt-4o-mini"),
                "defaultModel", "gpt-4o",
                "enabled", true
        );

        var result = mockMvc.perform(post("/api/v1/admin/ai-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("OpenAI Prod"))
                .andExpect(jsonPath("$.data.providerType").value("OPENAI"))
                .andExpect(jsonPath("$.data.apiKey").value(MASKED))
                .andExpect(jsonPath("$.data.defaultModel").value("gpt-4o"))
                .andExpect(jsonPath("$.data.models.length()").value(2))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("sk-test-secret-key");
        assertThat(aiProviderConfigRepository.findAll()).hasSize(1);
    }

    @Test
    void createProvider_forbiddenForReadOnlyPermission() throws Exception {
        Map<String, Object> request = Map.of("name", "X", "providerType", "OPENAI");

        mockMvc.perform(post("/api/v1/admin/ai-config")
                        .header("Authorization", "Bearer " + readerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createProvider_requiresName() throws Exception {
        Map<String, Object> request = Map.of("providerType", "OPENAI");

        mockMvc.perform(post("/api/v1/admin/ai-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createProvider_requiresBaseUrlForAzure() throws Exception {
        Map<String, Object> request = Map.of(
                "name", "Azure",
                "providerType", "AZURE_OPENAI"
        );

        mockMvc.perform(post("/api/v1/admin/ai-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateProvider_preservesApiKeyWhenMaskSent() throws Exception {
        AiProviderConfig saved = aiProviderConfigRepository.save(AiProviderConfig.builder()
                .name("Anthropic")
                .providerType(AiProviderType.ANTHROPIC)
                .encryptedApiKey("stored-key-value")
                .build());

        Map<String, Object> update = Map.of(
                "name", "Anthropic Renamed",
                "apiKey", MASKED
        );

        mockMvc.perform(put("/api/v1/admin/ai-config/" + saved.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Anthropic Renamed"))
                .andExpect(jsonPath("$.data.apiKey").value(MASKED));

        AiProviderConfig reloaded = aiProviderConfigRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getEncryptedApiKey()).isEqualTo("stored-key-value");
    }

    @Test
    void updateProvider_returnsNotFoundForUnknownId() throws Exception {
        Map<String, Object> update = Map.of("name", "X");

        mockMvc.perform(put("/api/v1/admin/ai-config/does-not-exist")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteProvider_removesProvider() throws Exception {
        AiProviderConfig saved = aiProviderConfigRepository.save(AiProviderConfig.builder()
                .name("Temp")
                .providerType(AiProviderType.OPENAI)
                .build());

        mockMvc.perform(delete("/api/v1/admin/ai-config/" + saved.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(aiProviderConfigRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    void deleteProvider_forbiddenForReadOnlyPermission() throws Exception {
        AiProviderConfig saved = aiProviderConfigRepository.save(AiProviderConfig.builder()
                .name("Temp")
                .providerType(AiProviderType.OPENAI)
                .build());

        mockMvc.perform(delete("/api/v1/admin/ai-config/" + saved.getId())
                        .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void testProvider_reportsFailureForUnreachableEndpoint() throws Exception {
        Map<String, Object> request = Map.of(
                "providerType", "OPENAI_COMPATIBLE",
                "baseUrl", "http://127.0.0.1:1/v1",
                "apiKey", "irrelevant"
        );

        mockMvc.perform(post("/api/v1/admin/ai-config/test")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.success").value(false));
    }

    @Test
    void testProvider_requiresBaseUrlForCompatibleProviders() throws Exception {
        Map<String, Object> request = Map.of("providerType", "OPENAI_COMPATIBLE");

        mockMvc.perform(post("/api/v1/admin/ai-config/test")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.message").value(
                        "Base URL is required for OPENAI_COMPATIBLE providers."));
    }

    @Test
    void testProvider_usesStoredCredentialsWhenMasked() throws Exception {
        AiProviderConfig saved = aiProviderConfigRepository.save(AiProviderConfig.builder()
                .name("Local")
                .providerType(AiProviderType.OPENAI_COMPATIBLE)
                .baseUrl("http://127.0.0.1:1/v1")
                .encryptedApiKey("stored-key")
                .build());

        Map<String, Object> request = Map.of("id", saved.getId(), "apiKey", MASKED);

        // Unreachable endpoint → failure, but the stored provider resolved: the error is a
        // connection/key problem, never the "Provider type is required." validation error.
        mockMvc.perform(post("/api/v1/admin/ai-config/test")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.message").value(
                        org.hamcrest.Matchers.not("Provider type is required.")));
    }

    // ── Web search config ──

    @Test
    void getWebSearchConfig_returnsDisabledDefault() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ai-config/web-search")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.allowInAskAi").value(false))
                .andExpect(jsonPath("$.data.provider").value("BRAVE"))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist());
    }

    @Test
    void updateWebSearchConfig_persistsAllowInAskAi() throws Exception {
        mockMvc.perform(put("/api/v1/admin/ai-config/web-search")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("enabled", true, "allowInAskAi", true, "apiKey", "brave-key"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowInAskAi").value(true));

        assertThat(webSearchConfigRepository.findById("singleton").orElseThrow().isAllowInAskAi()).isTrue();

        // Partial update leaves the flag untouched
        mockMvc.perform(put("/api/v1/admin/ai-config/web-search")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("provider", "SERPER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowInAskAi").value(true));
    }

    @Test
    void getWebSearchConfig_allowsReadPermission_deniesBasic() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ai-config/web-search")
                        .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/ai-config/web-search")
                        .header("Authorization", "Bearer " + basicToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateWebSearchConfig_masksAndPreservesKey() throws Exception {
        Map<String, Object> save = Map.of(
                "enabled", true, "provider", "TAVILY", "apiKey", "tvly-secret-123");

        var result = mockMvc.perform(put("/api/v1/admin/ai-config/web-search")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(save)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.provider").value("TAVILY"))
                .andExpect(jsonPath("$.data.apiKey").value(MASKED))
                .andReturn();
        // Plaintext key must never appear in the response, whether or not encryption is configured
        assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("tvly-secret-123");
        String storedAfterSave = webSearchConfigRepository.findById("singleton").orElseThrow().getEncryptedApiKey();
        assertThat(storedAfterSave).isNotBlank();

        // Masked key on a later update preserves the stored one and still applies other changes
        mockMvc.perform(put("/api/v1/admin/ai-config/web-search")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("provider", "BRAVE", "apiKey", MASKED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("BRAVE"))
                .andExpect(jsonPath("$.data.apiKey").value(MASKED));

        assertThat(webSearchConfigRepository.findById("singleton").orElseThrow().getEncryptedApiKey())
                .isEqualTo(storedAfterSave);
    }

    @Test
    void updateWebSearchConfig_forbiddenForReadOnly() throws Exception {
        mockMvc.perform(put("/api/v1/admin/ai-config/web-search")
                        .header("Authorization", "Bearer " + readerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                .andExpect(status().isForbidden());
    }

    // ── Anonymization config ──

    @Test
    void getAnonymizationConfig_returnsDisabledDefault() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ai-config/anonymization")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.scoreThreshold").value(0.5));
    }

    @Test
    void updateAnonymizationConfig_persistsAndClampsThreshold() throws Exception {
        mockMvc.perform(put("/api/v1/admin/ai-config/anonymization")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "presidioUrl", "http://localhost:5002/",
                                "scoreThreshold", 2.0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.presidioUrl").value("http://localhost:5002")) // trailing slash trimmed
                .andExpect(jsonPath("$.data.scoreThreshold").value(1.0));                  // clamped to [0,1]
    }

    @Test
    void anonymizationConfig_readAllowedForReader_writeForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ai-config/anonymization")
                        .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/admin/ai-config/anonymization")
                        .header("Authorization", "Bearer " + readerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/ai-config/anonymization")
                        .header("Authorization", "Bearer " + basicToken))
                .andExpect(status().isForbidden());
    }
}
