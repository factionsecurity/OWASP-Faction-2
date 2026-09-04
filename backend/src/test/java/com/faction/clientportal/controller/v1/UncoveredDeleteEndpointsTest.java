package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.repository.VulnerabilityStageCompletionRepository;
import com.faction.clientportal.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The DELETE endpoints that no test reached. Grouped rather than scattered because what they have
 * in common is the thing worth checking: each one removes something, and none of them had ever
 * been executed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UncoveredDeleteEndpointsTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private VulnerabilityStageCompletionRepository stageCompletionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private JwtService jwtService;

    private String token;
    private Assessment assessment;
    private Vulnerability vuln;

    @BeforeEach
    void setUp() {
        stageCompletionRepository.deleteAll();
        vulnerabilityRepository.deleteAll();
        assessmentRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role role = roleRepository.save(Role.builder()
                .name("SuperAdmin").permissions(List.of("super_admin")).build());
        User user = userRepository.save(User.builder()
                .username("deleter").firstName("D").lastName("El").email("d@test.com")
                .password("x").loginOption(LoginOption.NATIVE).roleIds(List.of(role.getId()))
                .isInternal(true).createdAt(LocalDateTime.now()).failedLoginAttempts(0).build());
        token = jwtService.generateToken(user.getUsername(),
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("super_admin")));

        assessment = assessmentRepository.save(Assessment.builder()
                .name("Q1").applicationId("app-1").organizationId("org-1")
                .assessmentTypeId("t").status("IN_PROGRESS")
                .createdAt(LocalDateTime.now()).build());
        vuln = vulnerabilityRepository.save(Vulnerability.builder()
                .name("SQLi").severity(VulnerabilitySeverity.HIGH)
                .assessmentId(assessment.getId()).order(0).status("Open")
                .openedAt(LocalDateTime.now()).subscribers(new ArrayList<>())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
    }

    // ── Stage completions ────────────────────────────────────────────────────

    @Test
    void clearingAStageCompletionRemovesIt() throws Exception {
        mockMvc.perform(put("/api/v1/assessments/{aid}/vulnerabilities/{id}/stage-completions/{s}",
                        assessment.getId(), vuln.getId(), "development")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(stageCompletionRepository.findByVulnerabilityIdAndStageId(vuln.getId(), "development"))
                .isPresent();

        mockMvc.perform(delete("/api/v1/assessments/{aid}/vulnerabilities/{id}/stage-completions/{s}",
                        assessment.getId(), vuln.getId(), "development")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(stageCompletionRepository.findByVulnerabilityIdAndStageId(vuln.getId(), "development"))
                .isEmpty();
    }

    @Test
    void clearingTheTerminalStageReopensTheFinding() throws Exception {
        mockMvc.perform(put("/api/v1/assessments/{aid}/vulnerabilities/{id}/stage-completions/{s}",
                        assessment.getId(), vuln.getId(), "production")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(reload().getStatus()).isEqualTo("Closed");

        mockMvc.perform(delete("/api/v1/assessments/{aid}/vulnerabilities/{id}/stage-completions/{s}",
                        assessment.getId(), vuln.getId(), "production")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // The terminal stage is the finding's own closed state, so clearing it has to reopen —
        // otherwise a finding sits Closed with nothing recording why.
        Vulnerability reopened = reload();
        assertThat(reopened.getStatus()).isEqualTo("Open");
        assertThat(reopened.getClosedAt()).isNull();
    }

    // ── Subscribers ──────────────────────────────────────────────────────────

    @Test
    void aSubscriberCanBeAddedAndRemoved() throws Exception {
        mockMvc.perform(post("/api/v1/assessments/{aid}/vulnerabilities/{id}/subscribers/{u}",
                        assessment.getId(), vuln.getId(), "deleter")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(delete("/api/v1/assessments/{aid}/vulnerabilities/{id}/subscribers/{u}",
                        assessment.getId(), vuln.getId(), "deleter")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        assertThat(reload().getSubscribers()).isEmpty();
    }

    @Test
    void removingSomeoneWhoWasNeverSubscribedIsHarmless() throws Exception {
        // Unsubscribing is idempotent by nature — a user clicking twice, or an email link
        // followed after the fact, must not fail.
        mockMvc.perform(delete("/api/v1/assessments/{aid}/vulnerabilities/{id}/subscribers/{u}",
                        assessment.getId(), vuln.getId(), "nobody")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void subscribersAreListedForTheThread() throws Exception {
        mockMvc.perform(post("/api/v1/assessments/{aid}/vulnerabilities/{id}/subscribers/{u}",
                        assessment.getId(), vuln.getId(), "deleter")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/assessments/{aid}/vulnerabilities/{id}/subscribers",
                        assessment.getId(), vuln.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("deleter"));
    }

    // ── Stage completion listing ─────────────────────────────────────────────

    @Test
    void stageCompletionsAreListedForEveryConfiguredStage() throws Exception {
        mockMvc.perform(get("/api/v1/assessments/{aid}/vulnerabilities/{id}/stage-completions",
                        assessment.getId(), vuln.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                // Every stage appears, complete or not — the panel renders the whole ladder.
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.data[-1:].terminal").value(org.hamcrest.Matchers.hasItem(true)));
    }

    private Vulnerability reload() {
        return vulnerabilityRepository.findById(vuln.getId()).orElseThrow();
    }
}
