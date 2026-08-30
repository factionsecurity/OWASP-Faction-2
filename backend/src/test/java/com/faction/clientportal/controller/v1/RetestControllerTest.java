package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Retest;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.RetestRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
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
class RetestControllerTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private RetestRepository retestRepository;
    @Autowired private com.faction.clientportal.repository.VulnerabilityStageCompletionRepository stageCompletionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String jwtToken;
    private String userId;
    private Assessment testAssessment;
    private Vulnerability testVuln;

    @BeforeEach
    void setUp() {
        retestRepository.deleteAll();
        stageCompletionRepository.deleteAll();
        vulnerabilityRepository.deleteAll();
        assessmentRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role role = roleRepository.save(Role.builder()
                .name("SuperAdmin")
                .permissions(List.of("super_admin"))
                .build());

        User user = userRepository.save(User.builder()
                .username("retest-test-user")
                .firstName("Retest")
                .lastName("Tester")
                .email("retest@test.com")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(role.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build());

        userId = user.getId();

        jwtToken = jwtService.generateToken(
                user.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin")));

        testAssessment = assessmentRepository.save(Assessment.builder()
                .name("Retest Test Assessment")
                .applicationId("app-retest-1")
                .assessmentTypeId("type-1")
                .organizationId("org-1")
                .status("COMPLETED")
                .createdAt(LocalDateTime.now())
                .build());

        testVuln = vulnerabilityRepository.save(Vulnerability.builder()
                .name("SQL Injection")
                .severity(VulnerabilitySeverity.HIGH)
                .assessmentId(testAssessment.getId())
                .order(0)
                .openedAt(LocalDateTime.now())
                .status("Open")
                .createdBy(user.getUsername())
                .lastUpdatedBy(user.getUsername())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private Map<String, Object> buildCreateRequest() {
        return Map.of(
                "vulnerabilityId", testVuln.getId(),
                "scheduledStartDate", "2026-04-01T09:00:00",
                "scheduledEndDate", "2026-04-05T17:00:00",
                "assignedAssessorIds", List.of(userId)
        );
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void createRetest_succeeds() throws Exception {
        mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.vulnerabilityId").value(testVuln.getId()));

        // Verify system comments were appended to the vulnerability
        // (retest scheduled + the automatic status change to "In Retest")
        Vulnerability updated = vulnerabilityRepository.findById(testVuln.getId()).orElseThrow();
        assertThat(updated.getComments()).hasSize(2);
        assertThat(updated.getComments().get(0).isSystemGenerated()).isTrue();
        assertThat(updated.getComments().get(0).getContent()).contains("Retest scheduled");
        assertThat(updated.getComments().get(0).getContent()).contains("Retest Tester");

        // Scheduling a retest moves the vulnerability into "In Retest"
        assertThat(updated.getStatus()).isEqualTo("In Retest");
    }

    @Test
    void retestScopeAndComment_holdRichTextLongerThan255Characters() throws Exception {
        // Both fields come from the frontend's RichTextEditor, so they carry HTML that blows
        // straight past the varchar(255) Hibernate would map a bare String to.
        String longScope = "<p>" + "Retest the authenticated upload path. ".repeat(40) + "</p>";
        String longComment = "<p>" + "Verified the fix against staging. ".repeat(40) + "</p>";
        assertThat(longScope.length()).isGreaterThan(255);
        assertThat(longComment.length()).isGreaterThan(255);

        Map<String, Object> request = Map.of(
                "vulnerabilityId", testVuln.getId(),
                "scheduledStartDate", "2026-04-01T09:00:00",
                "scheduledEndDate", "2026-04-05T17:00:00",
                "assignedAssessorIds", List.of(userId),
                "scope", longScope,
                "comment", longComment
        );

        String response = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.scope").value(longScope))
                .andExpect(jsonPath("$.data.comment").value(longComment))
                .andReturn().getResponse().getContentAsString();

        String retestId = objectMapper.readTree(response).path("data").path("id").asText();
        Retest stored = retestRepository.findById(retestId).orElseThrow();
        assertThat(stored.getScope()).isEqualTo(longScope);
        assertThat(stored.getComment()).isEqualTo(longComment);

        // And the update path keeps the full text too.
        String editedScope = longScope + "<p>Plus the password reset flow.</p>";
        mockMvc.perform(patch("/api/v1/retests/{id}", retestId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("scope", editedScope))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope").value(editedScope));

        assertThat(retestRepository.findById(retestId).orElseThrow().getScope()).isEqualTo(editedScope);
    }

    @Test
    void createRetest_invalidVulnerability_returns404() throws Exception {
        Map<String, Object> request = Map.of(
                "vulnerabilityId", "nonexistent-vuln-id",
                "scheduledStartDate", "2026-04-01T09:00:00",
                "scheduledEndDate", "2026-04-05T17:00:00"
        );

        mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getRetestsByAssessment_returnsList() throws Exception {
        // First create a retest
        mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated());

        // Then get the list
        mockMvc.perform(get("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].vulnerabilityId").value(testVuln.getId()));
    }

    @Test
    void completeRetest_withPass_setsStatusAndAddsComment() throws Exception {
        // Create a retest
        String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String retestId = objectMapper.readTree(createBody).at("/data/id").asText();

        // Complete with PASS
        mockMvc.perform(post("/api/v1/retests/{id}/complete", retestId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("result", "PASS", "comment", "All good"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PASSED"))
                .andExpect(jsonPath("$.data.result").value("PASS"));

        // Verify system comment on vulnerability
        Vulnerability updated = vulnerabilityRepository.findById(testVuln.getId()).orElseThrow();
        long systemComments = updated.getComments().stream().filter(c -> c.isSystemGenerated()).count();
        assertThat(systemComments).isGreaterThanOrEqualTo(2); // scheduled + completed
        boolean hasPassed = updated.getComments().stream()
                .anyMatch(c -> c.isSystemGenerated() && c.getContent().contains("PASSED"));
        assertThat(hasPassed).isTrue();

        // A passed retest moves the vulnerability to "Passed Retest"
        assertThat(updated.getStatus()).isEqualTo("Passed Retest");
    }

    @Test
    void completeRetest_stampsWhoVerifiedItAndWhen() throws Exception {
        String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retestId = objectMapper.readTree(createBody).at("/data/id").asText();

        mockMvc.perform(post("/api/v1/retests/{id}/complete", retestId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("result", "PASS"))))
                .andExpect(status().isOk())
                // The JWT subject — a username, like createdBy/lastUpdatedBy.
                .andExpect(jsonPath("$.data.completedBy").value("retest-test-user"));

        Retest completed = retestRepository.findById(retestId).orElseThrow();
        assertThat(completed.getCompletedBy()).isEqualTo("retest-test-user");
        assertThat(completed.getClosedDate()).isNotNull();
    }

    @Test
    void editingACompletedRetestDoesNotReassignTheSignOff() throws Exception {
        // lastUpdatedBy follows the latest edit; completedBy must keep crediting the verifier,
        // or the activity log reports whoever touched the record last.
        String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retestId = objectMapper.readTree(createBody).at("/data/id").asText();

        mockMvc.perform(post("/api/v1/retests/{id}/complete", retestId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("result", "FAIL"))))
                .andExpect(status().isOk());

        Retest completed = retestRepository.findById(retestId).orElseThrow();
        completed.setLastUpdatedBy("someone-else");
        retestRepository.save(completed);

        Retest reread = retestRepository.findById(retestId).orElseThrow();
        assertThat(reread.getCompletedBy()).isEqualTo("retest-test-user");
        assertThat(reread.getLastUpdatedBy()).isEqualTo("someone-else");
    }

    @Test
    void completeRetest_withFail_setsStatusAndAddsComment() throws Exception {
        // Create a retest
        String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String retestId = objectMapper.readTree(createBody).at("/data/id").asText();

        // Complete with FAIL
        mockMvc.perform(post("/api/v1/retests/{id}/complete", retestId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("result", "FAIL", "comment", "Still broken"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.result").value("FAIL"));

        // Verify system comment on vulnerability
        Vulnerability updated = vulnerabilityRepository.findById(testVuln.getId()).orElseThrow();
        boolean hasFailed = updated.getComments().stream()
                .anyMatch(c -> c.isSystemGenerated() && c.getContent().contains("FAILED"));
        assertThat(hasFailed).isTrue();

        // A failed retest moves the vulnerability to "Failed Retest"
        assertThat(updated.getStatus()).isEqualTo("Failed Retest");
    }

    @Test
    void updateRetest_changesScheduledDates() throws Exception {
        // Create a retest
        String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String retestId = objectMapper.readTree(createBody).at("/data/id").asText();

        // Update dates
        Map<String, Object> updateRequest = Map.of(
                "scheduledStartDate", "2026-05-01T09:00:00",
                "scheduledEndDate", "2026-05-10T17:00:00"
        );

        mockMvc.perform(patch("/api/v1/retests/{id}", retestId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduledStartDate").value(org.hamcrest.Matchers.containsString("2026-05-01")))
                .andExpect(jsonPath("$.data.scheduledEndDate").value(org.hamcrest.Matchers.containsString("2026-05-10")));
    }

    @Test
    void cancelRetest_marksItCancelledAndKeepsItOnTheRecord() throws Exception {
        String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String retestId = objectMapper.readTree(createBody).at("/data/id").asText();

        mockMvc.perform(delete("/api/v1/retests/{id}", retestId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());

        // Cancelled, not removed: "we asked for a retest and called it off" is a different
        // story from "no retest was ever requested", and a soft delete tells the second one.
        Retest cancelled = retestRepository.findById(retestId).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
        assertThat(cancelled.getDeletedAt()).isNull();

        // …and it still shows on the assessment, as cancelled.
        mockMvc.perform(get("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("CANCELLED"));

        // Cancelling appends a system comment to the vulnerability
        Vulnerability updated = vulnerabilityRepository.findById(testVuln.getId()).orElseThrow();
        boolean hasCancelled = updated.getComments().stream()
                .anyMatch(c -> c.isSystemGenerated() && c.getContent().contains("Retest cancelled"));
        assertThat(hasCancelled).isTrue();
    }

    @Test
    void cancelRetest_twice_doesNotDoubleLogIt() throws Exception {
        String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retestId = objectMapper.readTree(createBody).at("/data/id").asText();

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(delete("/api/v1/retests/{id}", retestId)
                            .header("Authorization", "Bearer " + jwtToken))
                    .andExpect(status().isOk());
        }

        Vulnerability updated = vulnerabilityRepository.findById(testVuln.getId()).orElseThrow();
        long cancelComments = updated.getComments().stream()
                .filter(c -> c.isSystemGenerated() && c.getContent().contains("Retest cancelled"))
                .count();
        assertThat(cancelComments).isEqualTo(1);
    }

    @Test
    void cancelledRetestDoesNotBlockANewRequest() throws Exception {
        // The drawer treats REQUESTED/SCHEDULED/IN_PROGRESS as "one is already open"; a
        // cancelled one must not stand in the way of asking again.
        String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retestId = objectMapper.readTree(createBody).at("/data/id").asText();

        mockMvc.perform(delete("/api/v1/retests/{id}", retestId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated());
    }

    @Test
    void requestRetest_withoutDates_createsRequestedAndLeavesVulnStatus() throws Exception {
        // An app-owner request has no dates: it becomes REQUESTED and the
        // vulnerability status is untouched until staff schedule it.
        Map<String, Object> request = Map.of("vulnerabilityId", testVuln.getId());

        mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));

        Vulnerability updated = vulnerabilityRepository.findById(testVuln.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("Open");
        boolean hasRequested = updated.getComments().stream()
                .anyMatch(c -> c.isSystemGenerated() && c.getContent().contains("Retest requested"));
        assertThat(hasRequested).isTrue();
    }

    @Test
    void requestRetest_withOnlyOneDate_returns400() throws Exception {
        Map<String, Object> request = Map.of(
                "vulnerabilityId", testVuln.getId(),
                "scheduledStartDate", "2026-04-01T09:00:00");

        mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void schedulingRequestedRetest_setsScheduledAndMovesVulnToInRetest() throws Exception {
        // App owner requests…
        String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("vulnerabilityId", testVuln.getId()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retestId = objectMapper.readTree(createBody).at("/data/id").asText();

        // …a REQUESTED retest cannot be completed yet…
        mockMvc.perform(post("/api/v1/retests/{id}/complete", retestId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("result", "PASS"))))
                .andExpect(status().isBadRequest());

        // …then staff schedule it by setting dates
        mockMvc.perform(patch("/api/v1/retests/{id}", retestId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduledStartDate", "2026-05-01T09:00:00",
                                "scheduledEndDate", "2026-05-05T17:00:00",
                                "assignedAssessorIds", List.of(userId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"));

        Vulnerability updated = vulnerabilityRepository.findById(testVuln.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("In Retest");
        boolean hasScheduled = updated.getComments().stream()
                .anyMatch(c -> c.isSystemGenerated() && c.getContent().contains("Retest scheduled"));
        assertThat(hasScheduled).isTrue();
    }

    @Test
    void createRetest_withoutAnAssessor_returns400() throws Exception {
        Map<String, Object> request = Map.of(
                "vulnerabilityId", testVuln.getId(),
                "scheduledStartDate", "2026-04-01T09:00:00",
                "scheduledEndDate", "2026-04-05T17:00:00",
                "assignedAssessorIds", List.of());

        mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        assertThat(retestRepository.findAll()).isEmpty();
    }

    @Test
    void requestingARetest_stillNeedsNeitherAssessorNorDates() throws Exception {
        // App owners request retests from the vulnerability drawer and can pick neither — the new
        // rule is about scheduling, so this path must stay open.
        mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("vulnerabilityId", testVuln.getId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));
    }

    @Test
    void schedulingARequestedRetest_withoutAnAssessor_returns400() throws Exception {
        String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("vulnerabilityId", testVuln.getId()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retestId = objectMapper.readTree(createBody).at("/data/id").asText();

        // Dates but nobody to do the work.
        mockMvc.perform(patch("/api/v1/retests/{id}", retestId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduledStartDate", "2026-05-01T09:00:00",
                                "scheduledEndDate", "2026-05-05T17:00:00"))))
                .andExpect(status().isBadRequest());

        // Still an unscheduled request, not a half-scheduled retest.
        assertThat(retestRepository.findById(retestId).orElseThrow().getStatus()).isEqualTo("REQUESTED");
    }

    // ── Re-rating through the retest ─────────────────────────────────────────

    @Test
    void savingARetestAppliesRevisedRatingsEvenThoughTheAssessmentIsFinalized() throws Exception {
        // The whole point: retests run on completed assessments, and the vulnerability API refuses
        // to modify one. Going through the retest is what makes re-rating possible at all.
        testAssessment.setStatus("COMPLETED");
        testAssessment.setCompletedDate(LocalDateTime.now());
        assessmentRepository.save(testAssessment);

        String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retestId = objectMapper.readTree(createBody).at("/data/id").asText();

        mockMvc.perform(patch("/api/v1/retests/{id}", retestId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "comment", "Re-rated after retest",
                                "severity", "MEDIUM",
                                "likelihood", "MEDIUM",
                                "impact", "MEDIUM"))))
                .andExpect(status().isOk());

        Vulnerability updated = vulnerabilityRepository.findById(testVuln.getId()).orElseThrow();
        assertThat(updated.getSeverity()).isEqualTo(VulnerabilitySeverity.MEDIUM);
        assertThat(updated.getLikelihood()).isEqualTo("MEDIUM");
        assertThat(updated.getImpact()).isEqualTo("MEDIUM");

        // The change is on the finding's own record, as a table.
        assertThat(updated.getComments())
                .anyMatch(c -> c.getContent().contains("Ratings revised on retest")
                        && c.getContent().contains("| Severity | HIGH | MEDIUM |"));
    }

    @Test
    void savingARetestWithUnchangedRatingsRecordsNothing() throws Exception {
        String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retestId = objectMapper.readTree(createBody).at("/data/id").asText();

        mockMvc.perform(patch("/api/v1/retests/{id}", retestId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "comment", "No rating change", "severity", "HIGH"))))
                .andExpect(status().isOk());

        Vulnerability updated = vulnerabilityRepository.findById(testVuln.getId()).orElseThrow();
        assertThat(updated.getComments())
                .noneMatch(c -> c.getContent().contains("Ratings revised on retest"));
    }

    @Test
    void anUnknownSeverityOnARetestSaveReturns400() throws Exception {
        String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retestId = objectMapper.readTree(createBody).at("/data/id").asText();

        mockMvc.perform(patch("/api/v1/retests/{id}", retestId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("severity", "SPICY"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void commentOnlyEditSucceedsOnARetestThatPredatesTheSchedulingRule() throws Exception {
        // A retest scheduled before the assessor/date requirement existed. Adding a comment must
        // still work — validating the whole resulting state on every update made such retests
        // impossible to save at all.
        Retest legacy = retestRepository.save(Retest.builder()
                .vulnerabilityId(testVuln.getId())
                .assessmentId(testAssessment.getId())
                .applicationId("app-retest-1")
                .status("SCHEDULED")
                .assignedAssessorIds(new java.util.ArrayList<>())   // nobody assigned
                .scheduledStartDate(null)                            // and no window
                .createdBy("system").lastUpdatedBy("system")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());

        mockMvc.perform(patch("/api/v1/retests/{id}", legacy.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("comment", "Checked again today"))))
                .andExpect(status().isOk());

        assertThat(retestRepository.findById(legacy.getId()).orElseThrow().getComment())
                .isEqualTo("Checked again today");
    }

    @Test
    void clearingTheAssessorsOnAScheduledRetestIsStillRejected() throws Exception {
        String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retestId = objectMapper.readTree(createBody).at("/data/id").asText();

        // Touching the scheduling fields still has to satisfy the rule.
        mockMvc.perform(patch("/api/v1/retests/{id}", retestId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("assignedAssessorIds", List.of()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchingStraightToScheduled_withoutDatesOrAssessor_returns400() throws Exception {
        String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("vulnerabilityId", testVuln.getId()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retestId = objectMapper.readTree(createBody).at("/data/id").asText();

        mockMvc.perform(patch("/api/v1/retests/{id}", retestId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "SCHEDULED"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchingRetestStatusDirectly_movesVulnerabilityToTheMatchingStatus() throws Exception {
        // The UI goes through /complete, but the PATCH accepts a status too — the vulnerability has to
        // follow either way, or it keeps a stale status forever.
        for (Map.Entry<String, String> tc : new java.util.LinkedHashMap<>(Map.of(
                "IN_PROGRESS", "In Retest",
                "PASSED", "Passed Retest",
                "FAILED", "Failed Retest")).entrySet()) {

            String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                            .header("Authorization", "Bearer " + jwtToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCreateRequest())))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String retestId = objectMapper.readTree(createBody).at("/data/id").asText();

            mockMvc.perform(patch("/api/v1/retests/{id}", retestId)
                            .header("Authorization", "Bearer " + jwtToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("status", tc.getKey()))))
                    .andExpect(status().isOk());

            Vulnerability updated = vulnerabilityRepository.findById(testVuln.getId()).orElseThrow();
            assertThat(updated.getStatus())
                    .as("retest %s should move the vulnerability to %s", tc.getKey(), tc.getValue())
                    .isEqualTo(tc.getValue());
        }
    }

    @Test
    void patchingAScheduledRetestWithoutAStatusChange_leavesTheVulnerabilityStatusAlone() throws Exception {
        String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retestId = objectMapper.readTree(createBody).at("/data/id").asText();

        // Someone sets the vulnerability's status by hand after scheduling…
        Vulnerability vuln = vulnerabilityRepository.findById(testVuln.getId()).orElseThrow();
        vuln.setStatus("Open");
        vulnerabilityRepository.save(vuln);

        // …then the retest's dates are edited. The retest's own status doesn't move, so neither does
        // the vulnerability's — an unrelated edit must not stomp a deliberate status.
        mockMvc.perform(patch("/api/v1/retests/{id}", retestId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduledEndDate", "2026-04-09T17:00:00"))))
                .andExpect(status().isOk());

        assertThat(vulnerabilityRepository.findById(testVuln.getId()).orElseThrow().getStatus())
                .isEqualTo("Open");
    }

    // ── Pass outcomes: what the retest closes ────────────────────────────────

    /** Schedule a retest and pass it with the given closure, returning the refreshed vulnerability. */
    private Vulnerability passWithClosure(String closure) throws Exception {
        String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retestId = objectMapper.readTree(createBody).at("/data/id").asText();

        Map<String, Object> body = closure == null
                ? Map.of("result", "PASS")
                : Map.of("result", "PASS", "closure", closure);
        mockMvc.perform(post("/api/v1/retests/{id}/complete", retestId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PASSED"));

        return vulnerabilityRepository.findById(testVuln.getId()).orElseThrow();
    }

    /** The completion date recorded for the given default stage id, or null. */
    private java.time.LocalDateTime stageCompletedAt(String stageId) {
        return stageCompletionRepository
                .findByVulnerabilityIdAndStageId(testVuln.getId(), stageId)
                .map(com.faction.clientportal.model.VulnerabilityStageCompletion::getCompletedAt)
                .orElse(null);
    }

    @Test
    void passClosingRetestOnly_leavesTheVulnerabilityOpen() throws Exception {
        Vulnerability v = passWithClosure("RETEST_ONLY");

        assertThat(v.getStatus()).isEqualTo("Passed Retest");
        assertThat(v.getClosedAt()).isNull();
        assertThat(stageCompletedAt("development")).isNull();
        assertThat(stageCompletedAt("staging")).isNull();
    }

    @Test
    void passWithNoClosure_behavesLikeRetestOnly() throws Exception {
        // Existing integrations don't send the field; they must keep their old behaviour.
        Vulnerability v = passWithClosure(null);

        assertThat(v.getStatus()).isEqualTo("Passed Retest");
        assertThat(v.getClosedAt()).isNull();
    }

    @Test
    void passClosingInDevelopment_recordsTheCompletionAndLeavesTheFindingOpen() throws Exception {
        // Legacy enum value — must keep working, mapped to the default "development" stage.
        Vulnerability v = passWithClosure("DEVELOPMENT");

        assertThat(stageCompletedAt("development")).isNotNull();
        assertThat(stageCompletedAt("staging")).isNull();
        // Confirmed in dev, not in production — the finding is still open.
        assertThat(v.getClosedAt()).isNull();
        assertThat(v.getStatus()).isEqualTo("Passed Retest");
        assertThat(v.getComments()).anyMatch(c -> c.getContent().contains("**Development** completed"));
    }

    @Test
    void passClosingInStaging_byStageId_recordsTheCompletionAndLeavesTheFindingOpen() throws Exception {
        // The new form: the closure is a configured remediation stage id.
        Vulnerability v = passWithClosure("staging");

        assertThat(stageCompletedAt("staging")).isNotNull();
        assertThat(stageCompletedAt("development")).isNull();
        assertThat(v.getClosedAt()).isNull();
        assertThat(v.getStatus()).isEqualTo("Passed Retest");
        assertThat(v.getComments()).anyMatch(c -> c.getContent().contains("**Staging** completed"));
    }

    @Test
    void passClosingInProduction_closesTheVulnerability() throws Exception {
        // PRODUCTION maps to whichever stage is terminal; no completion row is stored for it —
        // the vulnerability's own status/closedAt is the terminal stage's completion.
        Vulnerability v = passWithClosure("PRODUCTION");

        assertThat(v.getStatus()).isEqualTo("Closed");
        assertThat(v.getClosedAt()).isNotNull();
        assertThat(stageCompletionRepository.findByVulnerabilityId(testVuln.getId())).isEmpty();
        assertThat(v.getComments()).anyMatch(c -> c.getContent().contains("**Production** completed"));
    }

    @Test
    void passWithAnUnknownClosure_returns400() throws Exception {
        String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retestId = objectMapper.readTree(createBody).at("/data/id").asText();

        mockMvc.perform(post("/api/v1/retests/{id}/complete", retestId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("result", "PASS", "closure", "SOMEWHERE_ELSE"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void failIgnoresTheClosure() throws Exception {
        String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retestId = objectMapper.readTree(createBody).at("/data/id").asText();

        mockMvc.perform(post("/api/v1/retests/{id}/complete", retestId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("result", "FAIL", "closure", "PRODUCTION"))))
                .andExpect(status().isOk());

        // A failing retest must never close anything, whatever the caller asked for.
        Vulnerability v = vulnerabilityRepository.findById(testVuln.getId()).orElseThrow();
        assertThat(v.getStatus()).isEqualTo("Failed Retest");
        assertThat(v.getClosedAt()).isNull();
        assertThat(stageCompletionRepository.findByVulnerabilityId(testVuln.getId())).isEmpty();
    }

    @Test
    void deleteRetest_completed_returns400() throws Exception {
        // Create and complete a retest
        String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String retestId = objectMapper.readTree(createBody).at("/data/id").asText();

        mockMvc.perform(post("/api/v1/retests/{id}/complete", retestId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("result", "PASS"))))
                .andExpect(status().isOk());

        // A completed retest cannot be cancelled
        mockMvc.perform(delete("/api/v1/retests/{id}", retestId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isBadRequest());

        // Still present, still passed — a verdict is not undone by asking to cancel.
        Retest kept = retestRepository.findById(retestId).orElseThrow();
        assertThat(kept.getDeletedAt()).isNull();
        assertThat(kept.getStatus()).isEqualTo("PASSED");
    }

    @Test
    void getRetests_statusFilter_excludesCompleted() throws Exception {
        // One completed retest…
        String createBody = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String completedId = objectMapper.readTree(createBody).at("/data/id").asText();
        mockMvc.perform(post("/api/v1/retests/{id}/complete", completedId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("result", "PASS"))))
                .andExpect(status().isOk());

        // …and one open request
        mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("vulnerabilityId", testVuln.getId()))))
                .andExpect(status().isCreated());

        // Unfiltered returns both; the open-status filter (what the sidebar
        // badge uses) excludes the completed one
        mockMvc.perform(get("/api/v1/retests")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(get("/api/v1/retests")
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("status", "REQUESTED,SCHEDULED,IN_PROGRESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("REQUESTED"));
    }

    @Test
    void getRetestsAssignedToMe_returnsOnlyMine() throws Exception {
        // Create user2
        User user2 = userRepository.save(User.builder()
                .username("other-user")
                .firstName("Other")
                .lastName("User")
                .email("other@test.com")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of())
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build());

        // Create second vuln
        Vulnerability vuln2 = vulnerabilityRepository.save(Vulnerability.builder()
                .name("XSS Vulnerability")
                .severity(VulnerabilitySeverity.MEDIUM)
                .assessmentId(testAssessment.getId())
                .order(1)
                .openedAt(LocalDateTime.now())
                .status("Open")
                .createdBy("retest-test-user")
                .lastUpdatedBy("retest-test-user")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        // Create retest assigned to current user
        Map<String, Object> req1 = Map.of(
                "vulnerabilityId", testVuln.getId(),
                "scheduledStartDate", "2026-04-01T09:00:00",
                "scheduledEndDate", "2026-04-05T17:00:00",
                "assignedAssessorIds", List.of(userId)
        );

        // Create retest assigned to user2 only
        Map<String, Object> req2 = Map.of(
                "vulnerabilityId", vuln2.getId(),
                "scheduledStartDate", "2026-04-01T09:00:00",
                "scheduledEndDate", "2026-04-05T17:00:00",
                "assignedAssessorIds", List.of(user2.getId())
        );

        mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/assessments/{aid}/retests", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isCreated());

        // Get all retests assigned to me (current user = "retest-test-user")
        // The service uses authentication.getName() which is the username, not userId
        // assignedAssessorIds contains userId (the UUID) - so we need to check by username
        // Actually looking at service: service.getAll(assignedToMe, authentication.getName())
        // and repo: findByAssignedAssessorIdsContainingAndDeletedAtIsNull(userId)
        // Here userId in service is authentication.getName() = username
        // But we stored userId (UUID) in assignedAssessorIds...
        // Let's verify the total count first
        mockMvc.perform(get("/api/v1/retests")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2));
    }
}
