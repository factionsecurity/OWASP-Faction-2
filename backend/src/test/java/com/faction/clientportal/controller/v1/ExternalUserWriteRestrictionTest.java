package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.RetestRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.repository.VulnerabilityStageCompletionRepository;
import com.faction.clientportal.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * A customer-side account writes two things on a finding: a comment, and a retest request.
 * Everything else — status, fields, remediation stages, exceptions, creation, deletion — is staff
 * work, and the rule is the {@code isInternal} flag on the account rather than a permission.
 *
 * <p>So both users here hold the <em>same</em> full set of vulnerability permissions, and only the
 * flag differs. Without that the tests would prove nothing: an ordinary app owner is already
 * stopped by {@code @RequiresPermission}, which is not the rule under test — an admin can hand an
 * external role {@code vulnerabilities:edit:all} by mistake, and that must still not let a customer
 * close their own finding.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExternalUserWriteRestrictionTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private VulnerabilityStageCompletionRepository stageCompletionRepository;
    @Autowired private RetestRepository retestRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private JwtService jwtService;

    /** Everything a staff member gets on findings — deliberately handed to the external user too. */
    private static final List<String> FULL_VULN_PERMS = List.of(
            Permission.VULNERABILITIES_READ_ALL.getPermission(),
            Permission.VULNERABILITIES_CREATE_ALL.getPermission(),
            Permission.VULNERABILITIES_EDIT_ALL.getPermission(),
            Permission.VULNERABILITIES_DELETE_ALL.getPermission(),
            Permission.ASSESSMENTS_READ_ALL.getPermission());

    private Assessment assessment;
    private Vulnerability vuln;
    private String externalToken;
    private String internalToken;

    @BeforeEach
    void setUp() {
        retestRepository.deleteAll();
        stageCompletionRepository.deleteAll();
        vulnerabilityRepository.deleteAll();
        assessmentRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role role = roleRepository.save(Role.builder()
                .name("Over-permissioned").permissions(FULL_VULN_PERMS).build());

        externalToken = tokenFor(saveUser("client-user", false, role.getId()));
        internalToken = tokenFor(saveUser("staff-user", true, role.getId()));

        assessment = assessmentRepository.save(Assessment.builder()
                .name("Assessment").applicationId("app-1").organizationId("org-1")
                .assessmentTypeId("type-1").status("IN_PROGRESS")
                .createdAt(LocalDateTime.now()).build());

        vuln = vulnerabilityRepository.save(Vulnerability.builder()
                .name("XSS").severity(VulnerabilitySeverity.HIGH)
                .assessmentId(assessment.getId()).order(0)
                .status("Open").openedAt(LocalDateTime.now())
                .createdBy("system").lastUpdatedBy("system")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
    }

    // ── Refused ─────────────────────────────────────────────────────────────

    @Test
    void externalUser_cannotCloseTheFinding() throws Exception {
        patchStatus(externalToken, "Closed").andExpect(status().isForbidden());

        Vulnerability after = reload();
        assertThat(after.getStatus()).isEqualTo("Open");
        assertThat(after.getClosedAt()).isNull();
    }

    @Test
    void externalUser_cannotChangeTheStatusAtAll() throws Exception {
        // Not just closing: restating where a finding sits in its lifecycle is staff's call.
        patchStatus(externalToken, "Work in Progress").andExpect(status().isForbidden());

        assertThat(reload().getStatus()).isEqualTo("Open");
    }

    @Test
    void externalUser_cannotEditTheFinding() throws Exception {
        mockMvc.perform(patch("/api/v1/assessments/{aid}/vulnerabilities/{id}",
                        assessment.getId(), vuln.getId())
                        .header("Authorization", "Bearer " + externalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Renamed by the client"))))
                .andExpect(status().isForbidden());

        assertThat(reload().getName()).isEqualTo("XSS");
    }

    @Test
    void externalUser_cannotCompleteARemediationStage() throws Exception {
        // "production" is the terminal stage, so this is the other way to close a finding.
        mockMvc.perform(put("/api/v1/assessments/{aid}/vulnerabilities/{id}/stage-completions/{stage}",
                        assessment.getId(), vuln.getId(), "production")
                        .header("Authorization", "Bearer " + externalToken))
                .andExpect(status().isForbidden());

        // …and an earlier stage is refused too — remediation progress is recorded by staff.
        mockMvc.perform(put("/api/v1/assessments/{aid}/vulnerabilities/{id}/stage-completions/{stage}",
                        assessment.getId(), vuln.getId(), "development")
                        .header("Authorization", "Bearer " + externalToken))
                .andExpect(status().isForbidden());

        assertThat(reload().getClosedAt()).isNull();
        assertThat(stageCompletionRepository.findByVulnerabilityId(vuln.getId())).isEmpty();
    }

    @Test
    void externalUser_cannotAcceptTheRiskThroughAnException() throws Exception {
        mockMvc.perform(patch("/api/v1/assessments/{aid}/vulnerabilities/{id}/exception",
                        assessment.getId(), vuln.getId())
                        .header("Authorization", "Bearer " + externalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("exceptionApproval", "Approved"))))
                .andExpect(status().isForbidden());

        assertThat(reload().getExceptionApproval()).isNull();
    }

    @Test
    void externalUser_cannotCreateOrDeleteAFinding() throws Exception {
        mockMvc.perform(post("/api/v1/assessments/{aid}/vulnerabilities", assessment.getId())
                        .header("Authorization", "Bearer " + externalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Invented", "severity", "HIGH"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/assessments/{aid}/vulnerabilities/{id}",
                        assessment.getId(), vuln.getId())
                        .header("Authorization", "Bearer " + externalToken))
                .andExpect(status().isForbidden());

        assertThat(vulnerabilityRepository.findByIdAndDeletedAtIsNull(vuln.getId())).isPresent();
        assertThat(vulnerabilityRepository.findAll()).hasSize(1);
    }

    @Test
    void externalUser_canRequestARetestButNotScheduleOrVerifyIt() throws Exception {
        // Dates + assessors is scheduling, not requesting.
        mockMvc.perform(post("/api/v1/assessments/{aid}/retests", assessment.getId())
                        .header("Authorization", "Bearer " + externalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vulnerabilityId", vuln.getId(),
                                "scheduledStartDate", "2026-04-01T09:00:00",
                                "scheduledEndDate", "2026-04-05T17:00:00",
                                "assignedAssessorIds", List.of("someone")))))
                .andExpect(status().isForbidden());
        assertThat(retestRepository.findAll()).isEmpty();

        // The request they may make, staff then schedule.
        String body = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", assessment.getId())
                        .header("Authorization", "Bearer " + externalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("vulnerabilityId", vuln.getId()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retestId = objectMapper.readTree(body).at("/data/id").asText();

        // …but they can neither schedule that request themselves…
        mockMvc.perform(patch("/api/v1/retests/{id}", retestId)
                        .header("Authorization", "Bearer " + externalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduledStartDate", "2026-04-01T09:00:00",
                                "scheduledEndDate", "2026-04-05T17:00:00",
                                "assignedAssessorIds", List.of("someone")))))
                .andExpect(status().isForbidden());

        // …nor sign off on the result, which is the other way to close the finding.
        mockMvc.perform(post("/api/v1/retests/{id}/complete", retestId)
                        .header("Authorization", "Bearer " + externalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("result", "PASS", "closure", "production"))))
                .andExpect(status().isForbidden());

        assertThat(retestRepository.findById(retestId).orElseThrow().getStatus()).isEqualTo("REQUESTED");
        assertThat(reload().getStatus()).isEqualTo("Open");
        assertThat(reload().getClosedAt()).isNull();
    }

    // ── Still allowed ───────────────────────────────────────────────────────

    @Test
    void externalUser_canStillCommentAndRequestARetest() throws Exception {
        mockMvc.perform(post("/api/v1/assessments/{aid}/vulnerabilities/{id}/comments",
                        assessment.getId(), vuln.getId())
                        .header("Authorization", "Bearer " + externalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "<p>Fixed on our side</p>"))))
                .andExpect(status().isOk());

        String body = mockMvc.perform(post("/api/v1/assessments/{aid}/retests", assessment.getId())
                        .header("Authorization", "Bearer " + externalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("vulnerabilityId", vuln.getId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andReturn().getResponse().getContentAsString();

        // Asking for one includes withdrawing it again.
        String retestId = objectMapper.readTree(body).at("/data/id").asText();
        mockMvc.perform(delete("/api/v1/retests/{id}", retestId)
                        .header("Authorization", "Bearer " + externalToken))
                .andExpect(status().isOk());

        // Requesting a retest leaves the finding's own status alone.
        assertThat(reload().getStatus()).isEqualTo("Open");
    }

    // ── Control ─────────────────────────────────────────────────────────────

    @Test
    void aStaffAccountWithTheSamePermissionsStillCloses() throws Exception {
        // The only difference between these two users is isInternal, so this is what proves the
        // refusals above come from the account flag and not from the permission gate.
        patchStatus(internalToken, "Closed").andExpect(status().isOk());

        Vulnerability after = reload();
        assertThat(after.getStatus()).isEqualTo("Closed");
        assertThat(after.getClosedAt()).isNotNull();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.ResultActions patchStatus(String token, String status)
            throws Exception {
        return mockMvc.perform(patch("/api/v1/assessments/{aid}/vulnerabilities/{id}/status",
                        assessment.getId(), vuln.getId())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("status", status))));
    }

    private Vulnerability reload() {
        return vulnerabilityRepository.findById(vuln.getId()).orElseThrow();
    }

    private User saveUser(String username, boolean internal, String roleId) {
        return userRepository.save(User.builder()
                .username(username).firstName("T").lastName("U").email(username + "@test.com")
                .password("n/a").loginOption(LoginOption.NATIVE)
                .roleIds(List.of(roleId)).isInternal(internal)
                .createdAt(LocalDateTime.now()).failedLoginAttempts(0)
                .build());
    }

    private String tokenFor(User user) {
        return jwtService.generateToken(user.getUsername(),
                FULL_VULN_PERMS.stream().map(SimpleGrantedAuthority::new).toList());
    }
}
