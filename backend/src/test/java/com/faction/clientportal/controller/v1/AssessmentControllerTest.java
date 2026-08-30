package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.CreateAssessmentRequest;
import com.faction.clientportal.dto.UpdateAssessmentRequest;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentSurvey;
import com.faction.clientportal.model.AssessmentType;
import com.faction.clientportal.model.SurveyStatus;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Organization;
import com.faction.clientportal.model.ReportTemplate;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssessmentControllerTest extends TestContainersConfig {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AssessmentRepository assessmentRepository;

    @Autowired
    private com.faction.clientportal.repository.AssessmentSurveyRepository assessmentSurveyRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private AssessmentTypeRepository assessmentTypeRepository;

    @Autowired
    private ReportTemplateRepository reportTemplateRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private Role superAdminRole;
    private User testUser;
    private String jwtToken;
    private Organization testOrganization;
    private Application testApplication;
    private AssessmentType testAssessmentType;
    private ReportTemplate testTemplate;

    @BeforeEach
    void setUp() {
        assessmentSurveyRepository.deleteAll();
        assessmentRepository.deleteAll();
        reportTemplateRepository.deleteAll();
        assessmentTypeRepository.deleteAll();
        applicationRepository.deleteAll();
        organizationRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        // Create SuperAdmin role
        superAdminRole = Role.builder()
                .name("SuperAdmin")
                .description("Super Administrator with full access")
                .permissions(List.of("super_admin"))
                .build();
        superAdminRole = roleRepository.save(superAdminRole);

        // Create test user
        testUser = User.builder()
                .username("testuser")
                .email("test@example.com")
                .password(passwordEncoder.encode("password"))
                .firstName("Test")
                .lastName("User")
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(superAdminRole.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .build();
        testUser = userRepository.save(testUser);

        // Generate JWT token
        jwtToken = jwtService.generateToken(
                testUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create test organization
        testOrganization = Organization.builder()
                .name("Test Organization")
                .description("Test Description")
                .build();
        testOrganization = organizationRepository.save(testOrganization);

        // Create test application
        testApplication = Application.builder()
                .name("Test Application")
                .description("Test App Description")
                .organizationId(testOrganization.getId())
                .createdAt(LocalDateTime.now())
                .build();
        testApplication = applicationRepository.save(testApplication);

        // Create test assessment type
        testAssessmentType = AssessmentType.builder()
                .name("Penetration Test")
                .description("Security assessment")
                .createdAt(LocalDateTime.now())
                .build();
        testAssessmentType = assessmentTypeRepository.save(testAssessmentType);

        // Create test report template
        testTemplate = ReportTemplate.builder()
                .name("Test Template")
                .description("Test template description")
                .assessmentTypeId(testAssessmentType.getId())
                .version(1)
                .active(true)
                .userDefinedFields(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .build();
        testTemplate = reportTemplateRepository.save(testTemplate);
    }

    @Test
    void testCreateAssessment_Success() throws Exception {
        CreateAssessmentRequest request = CreateAssessmentRequest.builder()
                .name("Test Assessment")
                .applicationId(testApplication.getId())
                .assessmentTypeId(testAssessmentType.getId())
                .reportTemplateId(testTemplate.getId())
                .assessorIds(List.of(testUser.getId()))
                .engagementManagerId(testUser.getId())
                .startDate(LocalDateTime.now())
                .plannedEndDate(LocalDateTime.now().plusDays(7))
                .scope("Test scope content")
                .initialFieldValues(new HashMap<>())
                .build();

        mockMvc.perform(post("/api/v1/assessments")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Test Assessment"))
                .andExpect(jsonPath("$.data.assessorIds", hasSize(1)))
                .andExpect(jsonPath("$.data.engagementManagerId").value(testUser.getId()))
                .andExpect(jsonPath("$.data.scope").value("Test scope content"));
    }

    @Test
    void testGetMetrics_Success() throws Exception {
        // Create assessments with different statuses
        createTestAssessment("Assessment 1", "DRAFT");
        createTestAssessment("Assessment 2", "IN_PROGRESS");
        createTestAssessment("Assessment 3", "COMPLETED");
        createTestAssessment("Assessment 4", "ON_HOLD");

        mockMvc.perform(get("/api/v1/assessments/metrics")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalCount").value(4))
                .andExpect(jsonPath("$.data.draftCount").value(1))
                .andExpect(jsonPath("$.data.inProgressCount").value(1))
                .andExpect(jsonPath("$.data.completedCount").value(1))
                .andExpect(jsonPath("$.data.onHoldCount").value(1));
    }

    @Test
    void testGetMetrics_WithOrganizationFilter() throws Exception {
        // Create assessments
        createTestAssessment("Assessment 1", "DRAFT");
        createTestAssessment("Assessment 2", "IN_PROGRESS");

        mockMvc.perform(get("/api/v1/assessments/metrics")
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("organizationId", testOrganization.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalCount").value(2));
    }

    @Test
    void testGetCalendarView_Success() throws Exception {
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusDays(30);

        // Create assessments with date ranges
        createTestAssessmentWithDates("Assessment 1", start.plusDays(5), start.plusDays(7));
        createTestAssessmentWithDates("Assessment 2", start.plusDays(10), start.plusDays(12));

        mockMvc.perform(get("/api/v1/assessments/calendar")
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("startDate", start.toString())
                        .param("endDate", end.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(2)));
    }

    @Test
    void testCheckConflicts_Found() throws Exception {
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusDays(7);

        // Create existing assessment
        Assessment existing = createTestAssessmentWithDates("Existing Assessment", start.plusDays(3), start.plusDays(5));
        existing.setAssessorIds(List.of(testUser.getId()));
        assessmentRepository.save(existing);

        // Check for conflicts with overlapping dates and same assessor
        String requestBody = objectMapper.writeValueAsString(new HashMap<String, Object>() {{
            put("assessmentId", null);
            put("assessorIds", List.of(testUser.getId()));
            put("startDate", start.plusDays(2).toString());
            put("endDate", start.plusDays(4).toString());
        }});

        mockMvc.perform(post("/api/v1/assessments/check-conflicts")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name").value("Existing Assessment"));
    }

    @Test
    void testCheckConflicts_None() throws Exception {
        LocalDateTime start = LocalDateTime.now();

        String requestBody = objectMapper.writeValueAsString(new HashMap<String, Object>() {{
            put("assessmentId", null);
            put("assessorIds", List.of(testUser.getId()));
            put("startDate", start.plusDays(10).toString());
            put("endDate", start.plusDays(12).toString());
        }});

        mockMvc.perform(post("/api/v1/assessments/check-conflicts")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void testExportToCsv_Success() throws Exception {
        createTestAssessment("Assessment 1", "DRAFT");
        createTestAssessment("Assessment 2", "IN_PROGRESS");

        mockMvc.perform(get("/api/v1/assessments/export/csv")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("ID,Name,Status")))
                .andExpect(content().string(containsString("Assessment 1")))
                .andExpect(content().string(containsString("Assessment 2")));
    }

    @Test
    void testUpdateAssessment_WithEngagementFields() throws Exception {
        Assessment assessment = createTestAssessment("Original Assessment", "DRAFT");

        UpdateAssessmentRequest updateRequest = UpdateAssessmentRequest.builder()
                .name("Updated Assessment")
                .status("IN_PROGRESS")
                .assessorIds(List.of(testUser.getId()))
                .engagementManagerId(testUser.getId())
                .remediationManagerId(testUser.getId())
                .scope("Updated scope")
                .build();

        mockMvc.perform(put("/api/v1/assessments/" + assessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Updated Assessment"))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.scope").value("Updated scope"))
                .andExpect(jsonPath("$.data.engagementManagerId").value(testUser.getId()));
    }

    @Test
    void testGetAssessment_Success() throws Exception {
        Assessment assessment = createTestAssessment("Test Assessment", "DRAFT");

        mockMvc.perform(get("/api/v1/assessments/" + assessment.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(assessment.getId()))
                .andExpect(jsonPath("$.data.name").value("Test Assessment"));
    }

    @Test
    void testDeleteAssessment_Success() throws Exception {
        Assessment assessment = createTestAssessment("Assessment to Delete", "DRAFT");

        mockMvc.perform(delete("/api/v1/assessments/" + assessment.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Verify soft delete
        Assessment deleted = assessmentRepository.findById(assessment.getId()).orElse(null);
        assert deleted != null;
        assert deleted.getDeletedAt() != null;
    }

    // ==================== assessments:edit:all PERMISSION TESTS ====================

    @Test
    void editAllUser_CanViewAllAssessments() throws Exception {
        User editAllUser = User.builder()
                .username("editalluser")
                .email("editall@example.com")
                .firstName("EditAll")
                .lastName("User")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .build();
        editAllUser = userRepository.save(editAllUser);

        createTestAssessment("Assessment Alpha", "DRAFT");
        createTestAssessment("Assessment Beta", "IN_PROGRESS");

        // assessments:edit:all also grants read:all access per the controller @PreAuthorize
        String token = jwtService.generateToken(
                editAllUser.getUsername(),
                List.of(
                        new SimpleGrantedAuthority("assessments:edit:all"),
                        new SimpleGrantedAuthority("assessments:read:all")
                )
        );

        // Can list all assessments
        mockMvc.perform(get("/api/v1/assessments")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void editAllUser_CanViewAssessmentById() throws Exception {
        User editAllUser = User.builder()
                .username("editallview")
                .email("editallview@example.com")
                .firstName("EditAllView")
                .lastName("User")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .build();
        editAllUser = userRepository.save(editAllUser);

        Assessment assessment = createTestAssessment("Viewable Assessment", "DRAFT");

        String token = jwtService.generateToken(
                editAllUser.getUsername(),
                List.of(
                        new SimpleGrantedAuthority("assessments:edit:all"),
                        new SimpleGrantedAuthority("assessments:read:all")
                )
        );

        mockMvc.perform(get("/api/v1/assessments/" + assessment.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Viewable Assessment"));
    }

    @Test
    void editAllUser_CanUpdateAssessment() throws Exception {
        User editAllUser = User.builder()
                .username("editalledit")
                .email("editalledit@example.com")
                .firstName("EditAllEdit")
                .lastName("User")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .build();
        editAllUser = userRepository.save(editAllUser);

        Assessment assessment = createTestAssessment("Editable Assessment", "DRAFT");

        String token = jwtService.generateToken(
                editAllUser.getUsername(),
                List.of(new SimpleGrantedAuthority("assessments:edit:all"))
        );

        UpdateAssessmentRequest updateRequest = UpdateAssessmentRequest.builder()
                .name("Editable Assessment Updated")
                .status("IN_PROGRESS")
                .assessorIds(List.of(testUser.getId()))
                .engagementManagerId(testUser.getId())
                .scope("Updated scope")
                .build();

        mockMvc.perform(put("/api/v1/assessments/" + assessment.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Editable Assessment Updated"))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
    }

    @Test
    void editAllUser_CannotDeleteAssessment() throws Exception {
        User editAllUser = User.builder()
                .username("editallnodelete")
                .email("editallnodelete@example.com")
                .firstName("EditAllNoDelete")
                .lastName("User")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .build();
        editAllUser = userRepository.save(editAllUser);

        Assessment assessment = createTestAssessment("Protected Assessment", "DRAFT");

        String token = jwtService.generateToken(
                editAllUser.getUsername(),
                List.of(new SimpleGrantedAuthority("assessments:edit:all"))
        );

        // Delete requires assessments:delete:all or super_admin
        mockMvc.perform(delete("/api/v1/assessments/" + assessment.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void userWithoutAssessmentPermissions_CannotViewOrEditAssessments() throws Exception {
        User noAccessUser = User.builder()
                .username("noaccessassess")
                .email("noaccess@assess.com")
                .firstName("NoAccess")
                .lastName("User")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .build();
        noAccessUser = userRepository.save(noAccessUser);

        Assessment assessment = createTestAssessment("Restricted Assessment", "DRAFT");

        String token = jwtService.generateToken(
                noAccessUser.getUsername(),
                List.of(new SimpleGrantedAuthority("applications:read:owned"))
        );

        mockMvc.perform(get("/api/v1/assessments")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/assessments/" + assessment.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void testCreateAssessment_ScopeLongerThan255Chars() throws Exception {
        // scope was varchar(255) (no columnDefinition) — real rich-text scopes
        // are far longer and failed to save.
        String longScope = "<p>" + "scope ".repeat(200) + "</p>";
        CreateAssessmentRequest request = CreateAssessmentRequest.builder()
                .name("Long Scope Assessment")
                .applicationId(testApplication.getId())
                .assessmentTypeId(testAssessmentType.getId())
                .reportTemplateId(testTemplate.getId())
                .assessorIds(List.of(testUser.getId()))
                .startDate(LocalDateTime.now())
                .plannedEndDate(LocalDateTime.now().plusDays(7))
                .scope(longScope)
                .initialFieldValues(new HashMap<>())
                .build();

        mockMvc.perform(post("/api/v1/assessments")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope").value(longScope));
    }

    @Test
    void testSearchAssessments_AssignedToMe_MatchesByUserId() throws Exception {
        // The JWT principal is the USERNAME; assignments store user IDs. The
        // controller must resolve username -> id or this filter matches nothing.
        Assessment mine = createTestAssessment("Assigned To Me", "IN_PROGRESS");
        mine.setAssessorIds(new ArrayList<>(List.of(testUser.getId())));
        assessmentRepository.save(mine);

        createTestAssessment("Someone Else's", "IN_PROGRESS");

        mockMvc.perform(get("/api/v1/assessments")
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("assignedToMe", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name").value("Assigned To Me"));
    }

    @Test
    void testSearchAssessments_AssignedToMe_MatchesEngagementManager() throws Exception {
        Assessment mine = createTestAssessment("Managed By Me", "IN_PROGRESS");
        mine.setEngagementManagerId(testUser.getId());
        assessmentRepository.save(mine);

        createTestAssessment("Unrelated", "IN_PROGRESS");

        mockMvc.perform(get("/api/v1/assessments")
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("assignedToMe", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name").value("Managed By Me"));
    }

    // ── Completion date ────────────────────────────────────────────────────────

    @Test
    void testUpdateAssessment_AcceptsAnExplicitCompletedDate() throws Exception {
        // Importers load historical work, so the record has to carry the date testing actually
        // finished rather than the moment the import ran.
        Assessment assessment = createTestAssessment("Historic Test", "IN_PROGRESS");

        mockMvc.perform(put("/api/v1/assessments/" + assessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Historic Test\",\"status\":\"Completed\","
                                + "\"completedDate\":\"2026-04-08T06:11:39\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("Completed"))
                .andExpect(jsonPath("$.data.completedDate").value("2026-04-08T06:11:39"));

        // Re-finalizing an already-completed assessment (what a re-run does) corrects the date.
        mockMvc.perform(put("/api/v1/assessments/" + assessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Historic Test\",\"status\":\"Completed\","
                                + "\"completedDate\":\"2026-05-01T10:00:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedDate").value("2026-05-01T10:00:00"));
    }

    @Test
    void testUpdateAssessment_StampsCompletionNowWhenNoDateGiven() throws Exception {
        Assessment assessment = createTestAssessment("Finished Today", "IN_PROGRESS");

        mockMvc.perform(put("/api/v1/assessments/" + assessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Finished Today\",\"status\":\"Completed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedDate").exists());

        org.assertj.core.api.Assertions
                .assertThat(assessmentRepository.findById(assessment.getId()).orElseThrow().getCompletedDate())
                .isAfter(LocalDateTime.now().minusMinutes(5));
    }

    // ── Status and open-survey filters ─────────────────────────────────────────

    @Test
    void testSearchAssessments_FilteredBySeveralStatuses() throws Exception {
        createTestAssessment("Drafted", "DRAFT");
        createTestAssessment("Running", "IN_PROGRESS");
        createTestAssessment("Paused", "ON_HOLD");

        mockMvc.perform(get("/api/v1/assessments")
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("statuses", "IN_PROGRESS", "on_hold"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].name", containsInAnyOrder("Running", "Paused")));

        // A status nothing is in returns nothing rather than falling through to everything.
        mockMvc.perform(get("/api/v1/assessments")
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("statuses", "PENDING_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void testSearchAssessments_OpenSurveysOnly() throws Exception {
        Assessment withOpen = createTestAssessment("Has Open Survey", "IN_PROGRESS");
        Assessment allDone = createTestAssessment("Surveys Finished", "IN_PROGRESS");
        createTestAssessment("No Surveys", "IN_PROGRESS");

        assessmentSurveyRepository.save(AssessmentSurvey.builder()
                .assessmentId(withOpen.getId()).templateName("Scoping")
                .status(SurveyStatus.INCOMPLETE).build());
        // A second, finished survey on the same assessment must not cancel the open one out.
        assessmentSurveyRepository.save(AssessmentSurvey.builder()
                .assessmentId(withOpen.getId()).templateName("Kickoff")
                .status(SurveyStatus.COMPLETE).build());
        assessmentSurveyRepository.save(AssessmentSurvey.builder()
                .assessmentId(allDone.getId()).templateName("Scoping")
                .status(SurveyStatus.COMPLETE).build());

        mockMvc.perform(get("/api/v1/assessments")
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("openSurveys", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name").value("Has Open Survey"));

        // Off (or absent) leaves the list alone.
        mockMvc.perform(get("/api/v1/assessments")
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("openSurveys", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)));
    }

    @Test
    void testSearchAssessments_OpenSurveysWithNoMatchesReturnsNothing() throws Exception {
        createTestAssessment("No Surveys At All", "IN_PROGRESS");

        mockMvc.perform(get("/api/v1/assessments")
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("openSurveys", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)))
                .andExpect(jsonPath("$.pagination.totalElements").value(0));
    }

    @Test
    void testSearchAssessments_StatusAndOpenSurveyFiltersCombine() throws Exception {
        Assessment running = createTestAssessment("Running With Survey", "IN_PROGRESS");
        Assessment drafted = createTestAssessment("Draft With Survey", "DRAFT");
        assessmentSurveyRepository.save(AssessmentSurvey.builder()
                .assessmentId(running.getId()).templateName("Scoping")
                .status(SurveyStatus.INCOMPLETE).build());
        assessmentSurveyRepository.save(AssessmentSurvey.builder()
                .assessmentId(drafted.getId()).templateName("Scoping")
                .status(SurveyStatus.INCOMPLETE).build());

        mockMvc.perform(get("/api/v1/assessments")
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("statuses", "IN_PROGRESS")
                        .param("openSurveys", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name").value("Running With Survey"));
    }

    // Helper methods
    private Assessment createTestAssessment(String name, String status) {
        Assessment assessment = Assessment.builder()
                .name(name)
                .applicationId(testApplication.getId())
                .assessmentTypeId(testAssessmentType.getId())
                .organizationId(testOrganization.getId())
                .reportTemplateId(testTemplate.getId())
                .reportTemplateVersion(testTemplate.getVersion())
                .templateName(testTemplate.getName())
                .fieldDefinitions(new ArrayList<>())
                .fieldValues(new HashMap<>())
                .status(status)
                .assessorIds(new ArrayList<>())
                .createdBy(testUser.getUsername())
                .createdAt(LocalDateTime.now())
                .build();
        return assessmentRepository.save(assessment);
    }

    private Assessment createTestAssessmentWithDates(String name, LocalDateTime startDate, LocalDateTime endDate) {
        Assessment assessment = Assessment.builder()
                .name(name)
                .applicationId(testApplication.getId())
                .assessmentTypeId(testAssessmentType.getId())
                .organizationId(testOrganization.getId())
                .reportTemplateId(testTemplate.getId())
                .reportTemplateVersion(testTemplate.getVersion())
                .templateName(testTemplate.getName())
                .fieldDefinitions(new ArrayList<>())
                .fieldValues(new HashMap<>())
                .status("IN_PROGRESS")
                .assessorIds(new ArrayList<>())
                .startDate(startDate)
                .plannedEndDate(endDate)
                .createdBy(testUser.getUsername())
                .createdAt(LocalDateTime.now())
                .build();
        return assessmentRepository.save(assessment);
    }
}
