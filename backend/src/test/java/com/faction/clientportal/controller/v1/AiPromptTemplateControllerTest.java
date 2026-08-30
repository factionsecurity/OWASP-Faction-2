package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.AiPromptScope;
import com.faction.clientportal.model.AiPromptTemplate;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.AiPromptTemplateRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiPromptTemplateControllerTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AiPromptTemplateRepository aiPromptTemplateRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String adminToken;
    private String readerToken;
    private String basicToken;

    @BeforeEach
    void setUp() {
        aiPromptTemplateRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role adminRole = roleRepository.save(Role.builder()
                .name("SuperAdmin").permissions(List.of("super_admin")).build());
        Role readerRole = roleRepository.save(Role.builder()
                .name("AiReader").permissions(List.of("ai:config:read")).build());
        Role basicRole = roleRepository.save(Role.builder()
                .name("Basic").permissions(List.of("vulnerabilities:read:all")).build());

        User admin = userRepository.save(user("ai-prompt-admin", adminRole));
        User reader = userRepository.save(user("ai-prompt-reader", readerRole));
        User basic = userRepository.save(user("ai-prompt-basic", basicRole));

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
                .firstName("Test").lastName("User")
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
    void createPrompt_persistsAllFields() throws Exception {
        Map<String, Object> request = Map.of(
                "name", "Executive Summary",
                "description", "High-level summary of all findings",
                "scope", "ASSESSMENT",
                "prompt", "Write an executive summary of all vulnerabilities in this assessment.",
                "enabled", true
        );

        mockMvc.perform(post("/api/v1/admin/ai-config/prompts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Executive Summary"))
                .andExpect(jsonPath("$.data.scope").value("ASSESSMENT"))
                .andExpect(jsonPath("$.data.enabled").value(true));

        assertThat(aiPromptTemplateRepository.findAll()).hasSize(1);
    }

    @Test
    void createPrompt_persistsWebAccessFlag() throws Exception {
        Map<String, Object> request = Map.of(
                "name", "Add References",
                "scope", "VULNERABILITY",
                "prompt", "Add authoritative reference links to this finding.",
                "allowWebAccess", true
        );

        mockMvc.perform(post("/api/v1/admin/ai-config/prompts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowWebAccess").value(true));

        assertThat(aiPromptTemplateRepository.findAll().get(0).isAllowWebAccess()).isTrue();
    }

    @Test
    void createPrompt_defaultsWebAccessOff() throws Exception {
        Map<String, Object> request = Map.of(
                "name", "Executive Summary",
                "scope", "ASSESSMENT",
                "prompt", "Summarize the findings."
        );

        mockMvc.perform(post("/api/v1/admin/ai-config/prompts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowWebAccess").value(false));
    }

    @Test
    void createPrompt_requiresNameScopeAndPrompt() throws Exception {
        mockMvc.perform(post("/api/v1/admin/ai-config/prompts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "X"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createPrompt_forbiddenForReadOnlyAndBasicUsers() throws Exception {
        Map<String, Object> request = Map.of(
                "name", "X", "scope", "ASSESSMENT", "prompt", "p");

        mockMvc.perform(post("/api/v1/admin/ai-config/prompts")
                        .header("Authorization", "Bearer " + readerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/ai-config/prompts")
                        .header("Authorization", "Bearer " + basicToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void getPrompts_allowsReadPermission() throws Exception {
        aiPromptTemplateRepository.save(prompt("Risk Analysis", AiPromptScope.VULNERABILITY, true));

        mockMvc.perform(get("/api/v1/admin/ai-config/prompts")
                        .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Risk Analysis"))
                .andExpect(jsonPath("$.data[0].prompt").value("prompt text"));
    }

    @Test
    void updatePrompt_appliesPartialChanges() throws Exception {
        AiPromptTemplate saved = aiPromptTemplateRepository.save(
                prompt("Old Name", AiPromptScope.ASSESSMENT, true));

        mockMvc.perform(put("/api/v1/admin/ai-config/prompts/" + saved.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "New Name", "enabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("New Name"))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.prompt").value("prompt text"));
    }

    @Test
    void deletePrompt_removesPrompt() throws Exception {
        AiPromptTemplate saved = aiPromptTemplateRepository.save(
                prompt("Temp", AiPromptScope.ASSESSMENT, true));

        mockMvc.perform(delete("/api/v1/admin/ai-config/prompts/" + saved.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        assertThat(aiPromptTemplateRepository.findById(saved.getId())).isEmpty();
    }

    private AiPromptTemplate prompt(String name, AiPromptScope scope, boolean enabled) {
        return AiPromptTemplate.builder()
                .name(name)
                .scope(scope)
                .prompt("prompt text")
                .enabled(enabled)
                .build();
    }
}
