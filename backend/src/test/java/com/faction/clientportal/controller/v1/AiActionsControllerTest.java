package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.AiPromptScope;
import com.faction.clientportal.model.AiPromptTemplate;
import com.faction.clientportal.model.AiProviderConfig;
import com.faction.clientportal.model.AiProviderType;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.AiPromptTemplateRepository;
import com.faction.clientportal.repository.AiProviderConfigRepository;
import com.faction.clientportal.repository.AssessmentRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiActionsControllerTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AiPromptTemplateRepository aiPromptTemplateRepository;
    @Autowired private AiProviderConfigRepository aiProviderConfigRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String basicToken;
    private String orgUserToken;
    private Assessment assessment;
    private AiPromptTemplate assessmentPrompt;

    @BeforeEach
    void setUp() {
        aiPromptTemplateRepository.deleteAll();
        aiProviderConfigRepository.deleteAll();
        assessmentRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role basicRole = roleRepository.save(Role.builder()
                .name("Pentester").permissions(List.of("assessments:read:all")).build());
        Role orgRole = roleRepository.save(Role.builder()
                .name("OrgUser").permissions(List.of("assessments:read:org")).build());

        User basic = userRepository.save(user("ai-actions-basic", basicRole, null));
        User orgUser = userRepository.save(user("ai-actions-org", orgRole, "org-A"));

        basicToken = jwtService.generateToken(basic.getUsername(),
                List.of(new SimpleGrantedAuthority("assessments:read:all")));
        orgUserToken = jwtService.generateToken(orgUser.getUsername(),
                List.of(new SimpleGrantedAuthority("assessments:read:org")));

        assessment = assessmentRepository.save(Assessment.builder()
                .name("Q3 Web App Test")
                .organizationId("org-B")
                .build());

        assessmentPrompt = aiPromptTemplateRepository.save(AiPromptTemplate.builder()
                .name("Executive Summary")
                .scope(AiPromptScope.ASSESSMENT)
                .prompt("Write an executive summary.")
                .enabled(true)
                .build());
        aiPromptTemplateRepository.save(AiPromptTemplate.builder()
                .name("Disabled Prompt")
                .scope(AiPromptScope.ASSESSMENT)
                .prompt("x")
                .enabled(false)
                .build());
        aiPromptTemplateRepository.save(AiPromptTemplate.builder()
                .name("Risk Analysis")
                .scope(AiPromptScope.VULNERABILITY)
                .prompt("y")
                .enabled(true)
                .build());
    }

    private User user(String username, Role role, String organizationId) {
        return User.builder()
                .username(username)
                .firstName("Test").lastName("User")
                .email(username + "@test.com")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(role.getId()))
                .organizationId(organizationId)
                .isInternal(organizationId == null)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
    }

    @Test
    void getPrompts_returnsOnlyEnabledPromptsForScope_withoutPromptText() throws Exception {
        mockMvc.perform(get("/api/v1/ai/prompts?scope=ASSESSMENT")
                        .header("Authorization", "Bearer " + basicToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Executive Summary"))
                .andExpect(jsonPath("$.data[0].prompt").doesNotExist());

        mockMvc.perform(get("/api/v1/ai/prompts?scope=VULNERABILITY")
                        .header("Authorization", "Bearer " + basicToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Risk Analysis"));
    }

    @Test
    void getPrompts_requiresAuthentication() throws Exception {
        // No custom AuthenticationEntryPoint is configured, so anonymous requests get 403
        mockMvc.perform(get("/api/v1/ai/prompts?scope=ASSESSMENT"))
                .andExpect(status().isForbidden());
    }

    @Test
    void executePrompt_failsCleanlyWhenNoProviderConfigured() throws Exception {
        Map<String, Object> request = Map.of(
                "promptId", assessmentPrompt.getId(),
                "assessmentId", assessment.getId());

        mockMvc.perform(post("/api/v1/ai/execute-prompt")
                        .header("Authorization", "Bearer " + basicToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.message").value(
                        org.hamcrest.Matchers.containsString("No enabled AI provider")));
    }

    @Test
    void executePrompt_reportsConnectionFailureForUnreachableProvider() throws Exception {
        aiProviderConfigRepository.save(AiProviderConfig.builder()
                .name("Local")
                .providerType(AiProviderType.OPENAI_COMPATIBLE)
                .baseUrl("http://127.0.0.1:1/v1")
                .defaultModel("test-model")
                .enabled(true)
                .build());

        Map<String, Object> request = Map.of(
                "promptId", assessmentPrompt.getId(),
                "assessmentId", assessment.getId());

        mockMvc.perform(post("/api/v1/ai/execute-prompt")
                        .header("Authorization", "Bearer " + basicToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.message").value(
                        org.hamcrest.Matchers.containsString("Could not reach the AI provider")));
    }

    @Test
    void executePrompt_rejectsUnknownOrDisabledPrompt() throws Exception {
        Map<String, Object> request = Map.of(
                "promptId", "does-not-exist",
                "assessmentId", assessment.getId());

        mockMvc.perform(post("/api/v1/ai/execute-prompt")
                        .header("Authorization", "Bearer " + basicToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.message").value("Prompt not found or disabled."));
    }

    @Test
    void executePrompt_requiresAssessmentId() throws Exception {
        Map<String, Object> request = Map.of("promptId", assessmentPrompt.getId());

        mockMvc.perform(post("/api/v1/ai/execute-prompt")
                        .header("Authorization", "Bearer " + basicToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void executePrompt_deniesAssessmentOutsideUsersOrganization() throws Exception {
        // orgUser belongs to org-A; the assessment belongs to org-B
        Map<String, Object> request = Map.of(
                "promptId", assessmentPrompt.getId(),
                "assessmentId", assessment.getId());

        mockMvc.perform(post("/api/v1/ai/execute-prompt")
                        .header("Authorization", "Bearer " + orgUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void ask_requiresQuestion() throws Exception {
        Map<String, Object> request = Map.of("assessmentId", assessment.getId());

        mockMvc.perform(post("/api/v1/ai/ask")
                        .header("Authorization", "Bearer " + basicToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.message").value(
                        org.hamcrest.Matchers.containsString("question or instruction is required")));
    }

    @Test
    void suggestTitle_requiresDescriptionOrDetails() throws Exception {
        Map<String, Object> request = Map.of(
                "assessmentId", assessment.getId(),
                "description", "<p> </p>");

        mockMvc.perform(post("/api/v1/ai/suggest-title")
                        .header("Authorization", "Bearer " + basicToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.message").value(
                        org.hamcrest.Matchers.containsString("description or details")));
    }

    @Test
    void suggestTitle_failsCleanlyWhenNoProviderConfigured() throws Exception {
        Map<String, Object> request = Map.of(
                "assessmentId", assessment.getId(),
                "description", "<p>The login form concatenates user input into SQL.</p>");

        mockMvc.perform(post("/api/v1/ai/suggest-title")
                        .header("Authorization", "Bearer " + basicToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.message").value(
                        org.hamcrest.Matchers.containsString("No enabled AI provider")));
    }

    @Test
    void suggestTitle_deniesAssessmentOutsideUsersOrganization() throws Exception {
        Map<String, Object> request = Map.of(
                "assessmentId", assessment.getId(),
                "description", "some description");

        mockMvc.perform(post("/api/v1/ai/suggest-title")
                        .header("Authorization", "Bearer " + orgUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void ask_deniesAssessmentOutsideUsersOrganization() throws Exception {
        Map<String, Object> request = Map.of(
                "assessmentId", assessment.getId(),
                "question", "Summarize this");

        mockMvc.perform(post("/api/v1/ai/ask")
                        .header("Authorization", "Bearer " + orgUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }
}
