package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.CreateSurveyTemplateRequest;
import com.faction.clientportal.dto.SurveyTemplateQuestionDto;
import com.faction.clientportal.dto.UpdateSurveyTemplateRequest;
import com.faction.clientportal.model.*;
import com.faction.clientportal.repository.*;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SurveyTemplateControllerTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SurveyTemplateRepository surveyTemplateRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String adminToken;
    private String createToken;
    private String noPermToken;

    @BeforeEach
    void setUp() {
        surveyTemplateRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role role = roleRepository.save(Role.builder()
                .name("TestRole").description("test").permissions(List.of("super_admin")).build());

        User user = userRepository.save(User.builder()
                .username("admin").email("admin@test.com")
                .password(passwordEncoder.encode("password"))
                .firstName("Admin").lastName("User")
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(role.getId()))
                .isInternal(true).createdAt(LocalDateTime.now()).build());

        adminToken  = jwtService.generateToken(user.getUsername(), List.of(new SimpleGrantedAuthority("super_admin")));
        createToken = jwtService.generateToken(user.getUsername(), List.of(new SimpleGrantedAuthority("survey:create")));
        noPermToken = jwtService.generateToken(user.getUsername(), List.of(new SimpleGrantedAuthority("assessments:read:team")));
    }

    private SurveyTemplate saveTemplate(String name, boolean active) {
        return surveyTemplateRepository.save(SurveyTemplate.builder()
                .name(name)
                .questions(List.of(
                        SurveyTemplateQuestion.builder()
                                .id(UUID.randomUUID().toString())
                                .text("Q1").fieldType(SurveyFieldType.TEXTAREA).order(0).build()
                ))
                .active(active)
                .createdBy("admin").lastUpdatedBy("admin")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());
    }

    // ── GET /survey-templates ─────────────────────────────────────────────────

    @Test
    void getAll_ReturnsAllTemplates() throws Exception {
        saveTemplate("Template A", true);
        saveTemplate("Template B", false);

        mockMvc.perform(get("/api/v1/survey-templates")
                        .header("Authorization", "Bearer " + noPermToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(2)));
    }

    @Test
    void getAll_WithActiveFilter_ReturnsOnlyActive() throws Exception {
        saveTemplate("Active Template", true);
        saveTemplate("Inactive Template", false);

        mockMvc.perform(get("/api/v1/survey-templates?active=true")
                        .header("Authorization", "Bearer " + noPermToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name").value("Active Template"));
    }

    @Test
    void getAll_Unauthenticated_ReturnsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/survey-templates"))
                .andExpect(status().isForbidden());
    }

    // ── GET /survey-templates/{id} ────────────────────────────────────────────

    @Test
    void getById_ReturnsTemplate() throws Exception {
        SurveyTemplate template = saveTemplate("My Survey", true);

        mockMvc.perform(get("/api/v1/survey-templates/" + template.getId())
                        .header("Authorization", "Bearer " + noPermToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(template.getId()))
                .andExpect(jsonPath("$.data.name").value("My Survey"))
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    void getById_NotFound_Returns404() throws Exception {
        mockMvc.perform(get("/api/v1/survey-templates/nonexistent")
                        .header("Authorization", "Bearer " + noPermToken))
                .andExpect(status().isNotFound());
    }

    // ── POST /survey-templates ────────────────────────────────────────────────

    @Test
    void create_AsSuperAdmin_ReturnsCreated() throws Exception {
        CreateSurveyTemplateRequest req = new CreateSurveyTemplateRequest();
        req.setName("Security Questionnaire");
        SurveyTemplateQuestionDto q1 = new SurveyTemplateQuestionDto();
        q1.setText("Do you use MFA?"); q1.setFieldType("YES_NO"); q1.setOrder(0);
        SurveyTemplateQuestionDto q2 = new SurveyTemplateQuestionDto();
        q2.setText("Describe your auth flow"); q2.setFieldType("TEXTAREA"); q2.setOrder(1);
        req.setQuestions(List.of(q1, q2));

        mockMvc.perform(post("/api/v1/survey-templates")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Security Questionnaire"))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.questions", hasSize(2)));
    }

    @Test
    void create_WithSurveyCreatePermission_ReturnsCreated() throws Exception {
        CreateSurveyTemplateRequest req = new CreateSurveyTemplateRequest();
        req.setName("Permitted Survey");

        mockMvc.perform(post("/api/v1/survey-templates")
                        .header("Authorization", "Bearer " + createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Permitted Survey"));
    }

    @Test
    void create_WithoutPermission_ReturnsForbidden() throws Exception {
        CreateSurveyTemplateRequest req = new CreateSurveyTemplateRequest();
        req.setName("Should Fail");

        mockMvc.perform(post("/api/v1/survey-templates")
                        .header("Authorization", "Bearer " + noPermToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_MissingName_ReturnsBadRequest() throws Exception {
        CreateSurveyTemplateRequest req = new CreateSurveyTemplateRequest();
        // name is blank

        mockMvc.perform(post("/api/v1/survey-templates")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_WithDropdownQuestion_StoresOptions() throws Exception {
        CreateSurveyTemplateRequest req = new CreateSurveyTemplateRequest();
        req.setName("Dropdown Survey");
        SurveyTemplateQuestionDto q = new SurveyTemplateQuestionDto();
        q.setText("Select priority"); q.setFieldType("DROPDOWN");
        q.setDropdownOptions(List.of("Low", "Medium", "High")); q.setOrder(0);
        req.setQuestions(List.of(q));

        mockMvc.perform(post("/api/v1/survey-templates")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.questions[0].fieldType").value("DROPDOWN"))
                .andExpect(jsonPath("$.data.questions[0].dropdownOptions", hasSize(3)));
    }

    // ── PUT /survey-templates/{id} ────────────────────────────────────────────

    @Test
    void update_AsSuperAdmin_UpdatesSuccessfully() throws Exception {
        SurveyTemplate template = saveTemplate("Old Name", true);

        UpdateSurveyTemplateRequest req = new UpdateSurveyTemplateRequest();
        req.setName("New Name");
        req.setActive(false);

        mockMvc.perform(put("/api/v1/survey-templates/" + template.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("New Name"))
                .andExpect(jsonPath("$.data.active").value(false));
    }

    @Test
    void update_NotFound_Returns404() throws Exception {
        UpdateSurveyTemplateRequest req = new UpdateSurveyTemplateRequest();
        req.setName("Ghost");

        mockMvc.perform(put("/api/v1/survey-templates/nonexistent")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    // ── DELETE /survey-templates/{id} ─────────────────────────────────────────

    @Test
    void delete_AsSuperAdmin_Returns204() throws Exception {
        SurveyTemplate template = saveTemplate("To Delete", true);

        mockMvc.perform(delete("/api/v1/survey-templates/" + template.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assert surveyTemplateRepository.findById(template.getId()).isEmpty();
    }

    @Test
    void delete_NotFound_Returns404() throws Exception {
        mockMvc.perform(delete("/api/v1/survey-templates/nonexistent")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_WithoutPermission_ReturnsForbidden() throws Exception {
        SurveyTemplate template = saveTemplate("Protected", true);

        mockMvc.perform(delete("/api/v1/survey-templates/" + template.getId())
                        .header("Authorization", "Bearer " + noPermToken))
                .andExpect(status().isForbidden());
    }
}
