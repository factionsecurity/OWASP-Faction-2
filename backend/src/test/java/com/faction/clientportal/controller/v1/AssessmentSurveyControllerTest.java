package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.AddAssessmentSurveyRequest;
import com.faction.clientportal.dto.SurveyResponseDto;
import com.faction.clientportal.dto.UpdateAssessmentSurveyRequest;
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
class AssessmentSurveyControllerTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SurveyTemplateRepository surveyTemplateRepository;
    @Autowired private AssessmentSurveyRepository assessmentSurveyRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String adminToken;
    private String editToken;
    private String completeToken;
    private String noPermToken;
    private String assessmentId;
    private SurveyTemplate savedTemplate;

    @BeforeEach
    void setUp() {
        assessmentSurveyRepository.deleteAll();
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

        adminToken    = jwtService.generateToken(user.getUsername(), List.of(new SimpleGrantedAuthority("super_admin")));
        editToken     = jwtService.generateToken(user.getUsername(), List.of(new SimpleGrantedAuthority("assessments:edit:all")));
        completeToken = jwtService.generateToken(user.getUsername(), List.of(new SimpleGrantedAuthority("survey:complete")));
        noPermToken   = jwtService.generateToken(user.getUsername(), List.of(new SimpleGrantedAuthority("assessments:read:team")));

        assessmentId = UUID.randomUUID().toString();

        savedTemplate = surveyTemplateRepository.save(SurveyTemplate.builder()
                .name("Security Survey")
                .questions(List.of(
                        SurveyTemplateQuestion.builder().id("q1").text("Do you use MFA?")
                                .fieldType(SurveyFieldType.YES_NO).order(0).build(),
                        SurveyTemplateQuestion.builder().id("q2").text("Describe auth flow")
                                .fieldType(SurveyFieldType.TEXTAREA).order(1).build()
                ))
                .active(true)
                .createdBy("admin").lastUpdatedBy("admin")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());
    }

    // ── POST /assessments/{id}/surveys ────────────────────────────────────────

    @Test
    void addSurvey_AsSuperAdmin_ReturnsCreatedWithSnapshot() throws Exception {
        AddAssessmentSurveyRequest req = new AddAssessmentSurveyRequest();
        req.setTemplateId(savedTemplate.getId());

        mockMvc.perform(post("/api/v1/assessments/" + assessmentId + "/surveys")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.templateId").value(savedTemplate.getId()))
                .andExpect(jsonPath("$.data.templateName").value("Security Survey"))
                .andExpect(jsonPath("$.data.assessmentId").value(assessmentId))
                .andExpect(jsonPath("$.data.status").value("INCOMPLETE"))
                .andExpect(jsonPath("$.data.responses", hasSize(2)))
                .andExpect(jsonPath("$.data.responses[0].questionText").value("Do you use MFA?"))
                .andExpect(jsonPath("$.data.responses[0].fieldType").value("YES_NO"))
                .andExpect(jsonPath("$.data.responses[0].answer").doesNotExist());
    }

    @Test
    void addSurvey_WithEditPermission_ReturnsCreated() throws Exception {
        AddAssessmentSurveyRequest req = new AddAssessmentSurveyRequest();
        req.setTemplateId(savedTemplate.getId());

        mockMvc.perform(post("/api/v1/assessments/" + assessmentId + "/surveys")
                        .header("Authorization", "Bearer " + editToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    @Test
    void addSurvey_WithoutPermission_ReturnsForbidden() throws Exception {
        AddAssessmentSurveyRequest req = new AddAssessmentSurveyRequest();
        req.setTemplateId(savedTemplate.getId());

        mockMvc.perform(post("/api/v1/assessments/" + assessmentId + "/surveys")
                        .header("Authorization", "Bearer " + noPermToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void addSurvey_TemplateNotFound_Returns404() throws Exception {
        AddAssessmentSurveyRequest req = new AddAssessmentSurveyRequest();
        req.setTemplateId("nonexistent-template");

        mockMvc.perform(post("/api/v1/assessments/" + assessmentId + "/surveys")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    // ── GET /assessments/{id}/surveys ─────────────────────────────────────────

    @Test
    void getByAssessment_ReturnsSurveyList() throws Exception {
        AssessmentSurvey survey = assessmentSurveyRepository.save(AssessmentSurvey.builder()
                .assessmentId(assessmentId)
                .templateId(savedTemplate.getId())
                .templateName("Security Survey")
                .status(SurveyStatus.INCOMPLETE)
                .responses(List.of())
                .createdBy("admin").lastUpdatedBy("admin")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());

        mockMvc.perform(get("/api/v1/assessments/" + assessmentId + "/surveys")
                        .header("Authorization", "Bearer " + noPermToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(survey.getId()));
    }

    @Test
    void getByAssessment_EmptyAssessment_ReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/assessments/" + assessmentId + "/surveys")
                        .header("Authorization", "Bearer " + noPermToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    // ── PUT /assessments/{id}/surveys/{surveyId} ──────────────────────────────

    @Test
    void updateSurvey_WithAnswers_UpdatesSuccessfully() throws Exception {
        AssessmentSurvey survey = assessmentSurveyRepository.save(AssessmentSurvey.builder()
                .assessmentId(assessmentId)
                .templateId(savedTemplate.getId())
                .templateName("Security Survey")
                .status(SurveyStatus.INCOMPLETE)
                .responses(List.of(
                        SurveyResponse.builder().questionId("q1").questionText("Do you use MFA?")
                                .fieldType(SurveyFieldType.YES_NO).order(0).build(),
                        SurveyResponse.builder().questionId("q2").questionText("Describe auth flow")
                                .fieldType(SurveyFieldType.TEXTAREA).order(1).build()
                ))
                .createdBy("admin").lastUpdatedBy("admin")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());

        SurveyResponseDto r1 = new SurveyResponseDto();
        r1.setQuestionId("q1"); r1.setAnswer("Yes"); r1.setOrder(0);
        SurveyResponseDto r2 = new SurveyResponseDto();
        r2.setQuestionId("q2"); r2.setAnswer("We use OAuth2 with PKCE"); r2.setOrder(1);

        UpdateAssessmentSurveyRequest req = new UpdateAssessmentSurveyRequest();
        req.setResponses(List.of(r1, r2));

        mockMvc.perform(put("/api/v1/assessments/" + assessmentId + "/surveys/" + survey.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INCOMPLETE"))
                .andExpect(jsonPath("$.data.responses[0].answer").value("Yes"))
                .andExpect(jsonPath("$.data.responses[1].answer").value("We use OAuth2 with PKCE"));
    }

    @Test
    void updateSurvey_MarkComplete_SetsStatusAndCompletedBy() throws Exception {
        AssessmentSurvey survey = assessmentSurveyRepository.save(AssessmentSurvey.builder()
                .assessmentId(assessmentId)
                .templateId(savedTemplate.getId())
                .templateName("Security Survey")
                .status(SurveyStatus.INCOMPLETE)
                .responses(List.of())
                .createdBy("admin").lastUpdatedBy("admin")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());

        UpdateAssessmentSurveyRequest req = new UpdateAssessmentSurveyRequest();
        req.setComplete(true);

        mockMvc.perform(put("/api/v1/assessments/" + assessmentId + "/surveys/" + survey.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETE"))
                .andExpect(jsonPath("$.data.completedBy").value("admin"))
                .andExpect(jsonPath("$.data.completedAt").isNotEmpty());
    }

    @Test
    void updateSurvey_WithSurveyCompletePermission_AllowsUpdate() throws Exception {
        AssessmentSurvey survey = assessmentSurveyRepository.save(AssessmentSurvey.builder()
                .assessmentId(assessmentId)
                .templateId(savedTemplate.getId())
                .templateName("Security Survey")
                .status(SurveyStatus.INCOMPLETE)
                .responses(List.of())
                .createdBy("admin").lastUpdatedBy("admin")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());

        UpdateAssessmentSurveyRequest req = new UpdateAssessmentSurveyRequest();
        req.setComplete(true);

        mockMvc.perform(put("/api/v1/assessments/" + assessmentId + "/surveys/" + survey.getId())
                        .header("Authorization", "Bearer " + completeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETE"));
    }

    @Test
    void updateSurvey_WithoutPermission_ReturnsForbidden() throws Exception {
        AssessmentSurvey survey = assessmentSurveyRepository.save(AssessmentSurvey.builder()
                .assessmentId(assessmentId)
                .templateId(savedTemplate.getId())
                .templateName("Security Survey")
                .status(SurveyStatus.INCOMPLETE)
                .responses(List.of())
                .createdBy("admin").lastUpdatedBy("admin")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());

        UpdateAssessmentSurveyRequest req = new UpdateAssessmentSurveyRequest();
        req.setComplete(true);

        mockMvc.perform(put("/api/v1/assessments/" + assessmentId + "/surveys/" + survey.getId())
                        .header("Authorization", "Bearer " + noPermToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    // ── DELETE /assessments/{id}/surveys/{surveyId} ───────────────────────────

    @Test
    void removeSurvey_Returns204() throws Exception {
        AssessmentSurvey survey = assessmentSurveyRepository.save(AssessmentSurvey.builder()
                .assessmentId(assessmentId)
                .templateId(savedTemplate.getId())
                .templateName("Security Survey")
                .status(SurveyStatus.INCOMPLETE)
                .responses(List.of())
                .createdBy("admin").lastUpdatedBy("admin")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());

        mockMvc.perform(delete("/api/v1/assessments/" + assessmentId + "/surveys/" + survey.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assert assessmentSurveyRepository.findById(survey.getId()).isEmpty();
    }

    @Test
    void removeSurvey_WithoutPermission_ReturnsForbidden() throws Exception {
        AssessmentSurvey survey = assessmentSurveyRepository.save(AssessmentSurvey.builder()
                .assessmentId(assessmentId)
                .templateId(savedTemplate.getId())
                .templateName("Security Survey")
                .status(SurveyStatus.INCOMPLETE)
                .responses(List.of())
                .createdBy("admin").lastUpdatedBy("admin")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());

        mockMvc.perform(delete("/api/v1/assessments/" + assessmentId + "/surveys/" + survey.getId())
                        .header("Authorization", "Bearer " + noPermToken))
                .andExpect(status().isForbidden());
    }
}
