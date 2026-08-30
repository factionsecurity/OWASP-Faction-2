package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.CreateChecklistTemplateRequest;
import com.faction.clientportal.dto.ChecklistTemplateQuestionDto;
import com.faction.clientportal.dto.UpdateChecklistTemplateRequest;
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
class ChecklistTemplateControllerTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ChecklistTemplateRepository checklistTemplateRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String adminToken;
    private String createToken;
    private String noPermToken;
    private String assessmentTypeId;

    @BeforeEach
    void setUp() {
        checklistTemplateRepository.deleteAll();
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

        adminToken   = jwtService.generateToken(user.getUsername(), List.of(new SimpleGrantedAuthority("super_admin")));
        createToken  = jwtService.generateToken(user.getUsername(), List.of(new SimpleGrantedAuthority("checklist:create")));
        noPermToken  = jwtService.generateToken(user.getUsername(), List.of(new SimpleGrantedAuthority("assessments:read:team")));

        assessmentTypeId = UUID.randomUUID().toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ChecklistTemplate saveTemplate(String name, String typeId, boolean active) {
        return checklistTemplateRepository.save(ChecklistTemplate.builder()
                .name(name)
                .assessmentTypeId(typeId)
                .questions(List.of(
                        ChecklistTemplateQuestion.builder().id(UUID.randomUUID().toString()).text("Q1").order(0).build()
                ))
                .active(active)
                .createdBy("admin").lastUpdatedBy("admin")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());
    }

    // ── GET /checklist-templates ──────────────────────────────────────────────

    @Test
    void getAll_ReturnsAllTemplates() throws Exception {
        saveTemplate("Template A", assessmentTypeId, true);
        saveTemplate("Template B", "other-type", true);

        mockMvc.perform(get("/api/v1/checklist-templates")
                        .header("Authorization", "Bearer " + noPermToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(2)));
    }

    @Test
    void getAll_WithAssessmentTypeId_ReturnsFilteredActiveTemplates() throws Exception {
        saveTemplate("Matching Active", assessmentTypeId, true);
        saveTemplate("Matching Inactive", assessmentTypeId, false);
        saveTemplate("Other Type", "other-type", true);

        mockMvc.perform(get("/api/v1/checklist-templates?assessmentTypeId=" + assessmentTypeId)
                        .header("Authorization", "Bearer " + noPermToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name").value("Matching Active"));
    }

    @Test
    void getAll_Unauthenticated_ReturnsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/checklist-templates"))
                .andExpect(status().isForbidden());
    }

    // ── GET /checklist-templates/{id} ─────────────────────────────────────────

    @Test
    void getById_ReturnsTemplate() throws Exception {
        ChecklistTemplate template = saveTemplate("My Template", assessmentTypeId, true);

        mockMvc.perform(get("/api/v1/checklist-templates/" + template.getId())
                        .header("Authorization", "Bearer " + noPermToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(template.getId()))
                .andExpect(jsonPath("$.data.name").value("My Template"))
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    void getById_NotFound_Returns404() throws Exception {
        mockMvc.perform(get("/api/v1/checklist-templates/nonexistent-id")
                        .header("Authorization", "Bearer " + noPermToken))
                .andExpect(status().isNotFound());
    }

    // ── POST /checklist-templates ─────────────────────────────────────────────

    @Test
    void create_AsSuperAdmin_ReturnsCreated() throws Exception {
        CreateChecklistTemplateRequest req = new CreateChecklistTemplateRequest();
        req.setName("Web App Checklist");
        req.setAssessmentTypeId(assessmentTypeId);
        req.setQuestions(List.of(
                buildQuestion("Check authentication", 0),
                buildQuestion("Check authorization", 1)
        ));

        mockMvc.perform(post("/api/v1/checklist-templates")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Web App Checklist"))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.questions", hasSize(2)))
                .andExpect(jsonPath("$.data.assessmentTypeId").value(assessmentTypeId));
    }

    @Test
    void create_WithChecklistCreatePermission_ReturnsCreated() throws Exception {
        CreateChecklistTemplateRequest req = new CreateChecklistTemplateRequest();
        req.setName("Permitted Checklist");
        req.setAssessmentTypeId(assessmentTypeId);

        mockMvc.perform(post("/api/v1/checklist-templates")
                        .header("Authorization", "Bearer " + createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Permitted Checklist"));
    }

    @Test
    void create_WithoutPermission_ReturnsForbidden() throws Exception {
        CreateChecklistTemplateRequest req = new CreateChecklistTemplateRequest();
        req.setName("Should Fail");
        req.setAssessmentTypeId(assessmentTypeId);

        mockMvc.perform(post("/api/v1/checklist-templates")
                        .header("Authorization", "Bearer " + noPermToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_MissingName_ReturnsBadRequest() throws Exception {
        CreateChecklistTemplateRequest req = new CreateChecklistTemplateRequest();
        req.setAssessmentTypeId(assessmentTypeId);
        // name is blank

        mockMvc.perform(post("/api/v1/checklist-templates")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // ── PUT /checklist-templates/{id} ─────────────────────────────────────────

    @Test
    void update_AsSuperAdmin_UpdatesSuccessfully() throws Exception {
        ChecklistTemplate template = saveTemplate("Old Name", assessmentTypeId, true);

        UpdateChecklistTemplateRequest req = new UpdateChecklistTemplateRequest();
        req.setName("New Name");
        req.setActive(false);

        mockMvc.perform(put("/api/v1/checklist-templates/" + template.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("New Name"))
                .andExpect(jsonPath("$.data.active").value(false));
    }

    @Test
    void update_NotFound_Returns404() throws Exception {
        UpdateChecklistTemplateRequest req = new UpdateChecklistTemplateRequest();
        req.setName("Ghost");

        mockMvc.perform(put("/api/v1/checklist-templates/nonexistent-id")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    // ── DELETE /checklist-templates/{id} ──────────────────────────────────────

    @Test
    void delete_AsSuperAdmin_Returns204() throws Exception {
        ChecklistTemplate template = saveTemplate("To Delete", assessmentTypeId, true);

        mockMvc.perform(delete("/api/v1/checklist-templates/" + template.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assert checklistTemplateRepository.findById(template.getId()).isEmpty();
    }

    @Test
    void delete_NotFound_Returns404() throws Exception {
        mockMvc.perform(delete("/api/v1/checklist-templates/nonexistent-id")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_WithoutPermission_ReturnsForbidden() throws Exception {
        ChecklistTemplate template = saveTemplate("Protected", assessmentTypeId, true);

        mockMvc.perform(delete("/api/v1/checklist-templates/" + template.getId())
                        .header("Authorization", "Bearer " + noPermToken))
                .andExpect(status().isForbidden());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ChecklistTemplateQuestionDto buildQuestion(String text, int order) {
        ChecklistTemplateQuestionDto q = new ChecklistTemplateQuestionDto();
        q.setText(text);
        q.setOrder(order);
        return q;
    }
}
