package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.AddAssessmentChecklistRequest;
import com.faction.clientportal.dto.ChecklistResponseDto;
import com.faction.clientportal.dto.UpdateAssessmentChecklistRequest;
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
class AssessmentChecklistControllerTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ChecklistTemplateRepository checklistTemplateRepository;
    @Autowired private AssessmentChecklistRepository assessmentChecklistRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String adminToken;
    private String editToken;
    private String noPermToken;
    private String assessmentId;
    private ChecklistTemplate savedTemplate;

    @BeforeEach
    void setUp() {
        assessmentChecklistRepository.deleteAll();
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

        adminToken  = jwtService.generateToken(user.getUsername(), List.of(new SimpleGrantedAuthority("super_admin")));
        editToken   = jwtService.generateToken(user.getUsername(), List.of(new SimpleGrantedAuthority("assessments:edit:all")));
        noPermToken = jwtService.generateToken(user.getUsername(), List.of(new SimpleGrantedAuthority("assessments:read:team")));

        assessmentId = UUID.randomUUID().toString();

        savedTemplate = checklistTemplateRepository.save(ChecklistTemplate.builder()
                .name("Test Checklist")
                .assessmentTypeId(UUID.randomUUID().toString())
                .questions(List.of(
                        ChecklistTemplateQuestion.builder().id("q1").text("Check login").order(0).build(),
                        ChecklistTemplateQuestion.builder().id("q2").text("Check logout").order(1).build()
                ))
                .active(true)
                .createdBy("admin").lastUpdatedBy("admin")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());
    }

    // ── POST /assessments/{id}/checklists ─────────────────────────────────────

    @Test
    void addChecklist_AsSuperAdmin_ReturnsCreatedWithSnapshot() throws Exception {
        AddAssessmentChecklistRequest req = new AddAssessmentChecklistRequest();
        req.setTemplateId(savedTemplate.getId());

        mockMvc.perform(post("/api/v1/assessments/" + assessmentId + "/checklists")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.templateId").value(savedTemplate.getId()))
                .andExpect(jsonPath("$.data.templateName").value("Test Checklist"))
                .andExpect(jsonPath("$.data.assessmentId").value(assessmentId))
                .andExpect(jsonPath("$.data.responses", hasSize(2)))
                .andExpect(jsonPath("$.data.responses[0].questionText").value("Check login"))
                .andExpect(jsonPath("$.data.responses[0].result").doesNotExist());
    }

    @Test
    void addChecklist_WithEditPermission_ReturnsCreated() throws Exception {
        AddAssessmentChecklistRequest req = new AddAssessmentChecklistRequest();
        req.setTemplateId(savedTemplate.getId());

        mockMvc.perform(post("/api/v1/assessments/" + assessmentId + "/checklists")
                        .header("Authorization", "Bearer " + editToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    @Test
    void addChecklist_WithoutPermission_ReturnsForbidden() throws Exception {
        AddAssessmentChecklistRequest req = new AddAssessmentChecklistRequest();
        req.setTemplateId(savedTemplate.getId());

        mockMvc.perform(post("/api/v1/assessments/" + assessmentId + "/checklists")
                        .header("Authorization", "Bearer " + noPermToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void addChecklist_TemplateNotFound_Returns404() throws Exception {
        AddAssessmentChecklistRequest req = new AddAssessmentChecklistRequest();
        req.setTemplateId("nonexistent-template");

        mockMvc.perform(post("/api/v1/assessments/" + assessmentId + "/checklists")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    // ── GET /assessments/{id}/checklists ──────────────────────────────────────

    @Test
    void getByAssessment_ReturnsChecklistList() throws Exception {
        // Add a checklist first
        AssessmentChecklist checklist = assessmentChecklistRepository.save(AssessmentChecklist.builder()
                .assessmentId(assessmentId)
                .templateId(savedTemplate.getId())
                .templateName("Test Checklist")
                .responses(List.of())
                .createdBy("admin").lastUpdatedBy("admin")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());

        mockMvc.perform(get("/api/v1/assessments/" + assessmentId + "/checklists")
                        .header("Authorization", "Bearer " + noPermToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(checklist.getId()));
    }

    @Test
    void getByAssessment_EmptyAssessment_ReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/assessments/" + assessmentId + "/checklists")
                        .header("Authorization", "Bearer " + noPermToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    // ── PUT /assessments/{id}/checklists/{checklistId} ────────────────────────

    @Test
    void updateResponses_UpdatesSuccessfully() throws Exception {
        AssessmentChecklist checklist = assessmentChecklistRepository.save(AssessmentChecklist.builder()
                .assessmentId(assessmentId)
                .templateId(savedTemplate.getId())
                .templateName("Test Checklist")
                .responses(List.of(
                        ChecklistResponse.builder().questionId("q1").questionText("Check login").order(0).build(),
                        ChecklistResponse.builder().questionId("q2").questionText("Check logout").order(1).build()
                ))
                .createdBy("admin").lastUpdatedBy("admin")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());

        ChecklistResponseDto r1 = new ChecklistResponseDto();
        r1.setQuestionId("q1");
        r1.setQuestionText("Check login");
        r1.setResult("PASS");
        r1.setComment("Works fine");
        r1.setOrder(0);

        ChecklistResponseDto r2 = new ChecklistResponseDto();
        r2.setQuestionId("q2");
        r2.setQuestionText("Check logout");
        r2.setResult("FAIL");
        r2.setOrder(1);

        UpdateAssessmentChecklistRequest req = new UpdateAssessmentChecklistRequest();
        req.setResponses(List.of(r1, r2));

        mockMvc.perform(put("/api/v1/assessments/" + assessmentId + "/checklists/" + checklist.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.responses[0].result").value("PASS"))
                .andExpect(jsonPath("$.data.responses[0].comment").value("Works fine"))
                .andExpect(jsonPath("$.data.responses[1].result").value("FAIL"));
    }

    // ── DELETE /assessments/{id}/checklists/{checklistId} ─────────────────────

    @Test
    void removeChecklist_Returns204() throws Exception {
        AssessmentChecklist checklist = assessmentChecklistRepository.save(AssessmentChecklist.builder()
                .assessmentId(assessmentId)
                .templateId(savedTemplate.getId())
                .templateName("Test Checklist")
                .responses(List.of())
                .createdBy("admin").lastUpdatedBy("admin")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());

        mockMvc.perform(delete("/api/v1/assessments/" + assessmentId + "/checklists/" + checklist.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assert assessmentChecklistRepository.findById(checklist.getId()).isEmpty();
    }

    @Test
    void removeChecklist_WithoutPermission_ReturnsForbidden() throws Exception {
        AssessmentChecklist checklist = assessmentChecklistRepository.save(AssessmentChecklist.builder()
                .assessmentId(assessmentId)
                .templateId(savedTemplate.getId())
                .templateName("Test Checklist")
                .responses(List.of())
                .createdBy("admin").lastUpdatedBy("admin")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());

        mockMvc.perform(delete("/api/v1/assessments/" + assessmentId + "/checklists/" + checklist.getId())
                        .header("Authorization", "Bearer " + noPermToken))
                .andExpect(status().isForbidden());
    }
}
