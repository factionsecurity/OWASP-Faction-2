package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssignedUser;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.security.RequiresPermissionAuthorizationManager;
import com.faction.clientportal.service.AssessmentService;
import com.faction.clientportal.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the assessment summary counts (active / total) that back the nav badge:
 * the status rollup and the two-layer authorization (permission gate + row-level
 * scope). Scope tests prove a scoped caller's totals only reflect their slice —
 * the confidentiality guarantee the {@code @RequiresPermission} annotation alone
 * does NOT provide.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssessmentSummaryTest extends TestContainersConfig {

    @Autowired private AssessmentService assessmentService;
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private UserRepository userRepository;

    private static final String ORG_A = "org-A";
    private static final String ORG_B = "org-B";

    @BeforeEach
    void setUp() {
        assessmentRepository.deleteAll();
        applicationRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ── Rollup ────────────────────────────────────────────────────────────────

    @Test
    void summary_rollsUpActiveAndTotal() {
        assessment(ORG_A, "app-1", "IN_PROGRESS", null);
        assessment(ORG_A, "app-1", "SCHEDULED", null);
        assessment(ORG_A, "app-1", "COMPLETED", null);
        assessment(ORG_A, "app-1", "APPROVED", null);
        assessment(ORG_A, "app-1", "ARCHIVED", null);
        assessment(ORG_A, "app-1", "IN_PROGRESS", LocalDateTime.now()); // soft-deleted (excluded)

        var s = assessmentService.assessmentSummary(superAdmin());

        assertThat(s.getTotal()).isEqualTo(5L);  // all non-deleted
        assertThat(s.getActive()).isEqualTo(2L); // IN_PROGRESS + SCHEDULED
    }

    // ── Scope ─────────────────────────────────────────────────────────────────

    @Test
    void summary_orgScopedUser_seesOnlyTheirOrg() {
        assessment(ORG_A, "app-a", "IN_PROGRESS", null);
        assessment(ORG_B, "app-b", "IN_PROGRESS", null);
        user("org-user", ORG_A);

        var s = assessmentService.assessmentSummary(
                auth("org-user", Permission.ASSESSMENTS_READ_ORG.getPermission()));

        assertThat(s.getTotal()).isEqualTo(1L); // ORG_A only, not 2
        assertThat(s.getActive()).isEqualTo(1L);
    }

    @Test
    void summary_orgScopedUserWithNoResolvableOrg_failsClosed() {
        assessment(ORG_A, "app-a", "IN_PROGRESS", null);
        assessment(ORG_B, "app-b", "IN_PROGRESS", null);
        // "ghost" has no user record → resolveOrgId returns null; must see nothing, not everything.

        var s = assessmentService.assessmentSummary(
                auth("ghost", Permission.ASSESSMENTS_READ_ORG.getPermission()));

        assertThat(s.getTotal()).isZero();
        assertThat(s.getActive()).isZero();
    }

    @Test
    void summary_ownedScopedUser_seesOnlyOwnedApplications() {
        var u = user("owned-user", ORG_A);
        var appX = ownedApp(ORG_A, "Owned App X", u.getId()).getId();
        var appY = application(ORG_A, "Unowned App Y").getId();
        assessment(ORG_A, appX, "IN_PROGRESS", null);
        assessment(ORG_A, appY, "IN_PROGRESS", null);

        var s = assessmentService.assessmentSummary(
                auth("owned-user", Permission.ASSESSMENTS_READ_OWNED.getPermission()));

        assertThat(s.getTotal()).isEqualTo(1L); // owned app only
        assertThat(s.getActive()).isEqualTo(1L);
    }

    @Test
    void summary_readAll_isUnrestricted() {
        assessment(ORG_A, "app-a", "IN_PROGRESS", null);
        assessment(ORG_B, "app-b", "IN_PROGRESS", null);

        assertThat(assessmentService.assessmentSummary(
                auth("all-user", Permission.ASSESSMENTS_READ_ALL.getPermission())).getTotal()).isEqualTo(2L);
    }

    @Test
    void summary_teamScopedUser_countsOnlyTheirTeamsAssessments() {
        // :read:team used to be unrestricted (the tier wasn't enforced anywhere). It now counts
        // only the caller's teams — and a user in no team counts nothing rather than everything.
        assessment(ORG_A, "app-a", "IN_PROGRESS", null);
        assessment(ORG_B, "app-b", "IN_PROGRESS", null);
        user("team-user", ORG_A);

        assertThat(assessmentService.assessmentSummary(
                auth("team-user", Permission.ASSESSMENTS_READ_TEAM.getPermission())).getTotal()).isZero();
    }

    // ── Gate (endpoint-level) ───────────────────────────────────────────────────

    @Test
    void endpoint_withoutAssessmentReadPermission_isForbidden() throws Exception {
        var token = jwtService.generateToken("no-perm",
                List.of(new SimpleGrantedAuthority(Permission.APPLICATIONS_READ_ALL.getPermission())));
        mockMvc.perform(get("/api/v1/assessments/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void endpoint_withAssessmentReadPermission_isOk() throws Exception {
        var token = jwtService.generateToken("admin-user",
                List.of(new SimpleGrantedAuthority(RequiresPermissionAuthorizationManager.SUPER_ADMIN)));
        mockMvc.perform(get("/api/v1/assessments/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.active").exists())
                .andExpect(jsonPath("$.data.total").exists());
    }

    @Test
    void endpoint_withNonSuperAdminAssessmentRead_isOk() throws Exception {
        // The gate must admit ordinary assessment-read permissions, not just super_admin.
        var token = jwtService.generateToken("reader",
                List.of(new SimpleGrantedAuthority(Permission.ASSESSMENTS_READ_ALL.getPermission())));
        mockMvc.perform(get("/api/v1/assessments/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Assessment assessment(String orgId, String applicationId, String status, LocalDateTime deletedAt) {
        return assessmentRepository.save(Assessment.builder()
                .name("Assessment " + applicationId + "-" + System.nanoTime())
                .applicationId(applicationId)
                .assessmentTypeId("type-1")
                .organizationId(orgId)
                .status(status)
                .deletedAt(deletedAt)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private User user(String username, String orgId) {
        return userRepository.save(User.builder()
                .username(username)
                .firstName("T").lastName("U")
                .email(username + "@test.com")
                .password("x")
                .loginOption(LoginOption.NATIVE)
                .organizationId(orgId)
                .isInternal(false)
                .failedLoginAttempts(0)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private Application application(String orgId, String name) {
        return applicationRepository.save(Application.builder()
                .name(name + "-" + System.nanoTime())
                .organizationId(orgId)
                .build());
    }

    private Application ownedApp(String orgId, String name, String assignedUserId) {
        return applicationRepository.save(Application.builder()
                .name(name + "-" + System.nanoTime())
                .organizationId(orgId)
                .assignedUsers(List.of(AssignedUser.builder()
                        .userId(assignedUserId).accessLevel("WRITE").build()))
                .build());
    }

    private Authentication superAdmin() {
        return auth("super", RequiresPermissionAuthorizationManager.SUPER_ADMIN);
    }

    private Authentication auth(String username, String... authorities) {
        var granted = Arrays.stream(authorities)
                .map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a))
                .toList();
        return new UsernamePasswordAuthenticationToken(username, null, granted);
    }
}
