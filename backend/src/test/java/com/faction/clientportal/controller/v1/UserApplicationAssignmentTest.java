package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.AssignedUser;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Organization;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.JwtService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests the user-centric application-assignment endpoints that back the Users
 * page "specific applications" access mode.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserApplicationAssignmentTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private JwtService jwtService;

    private String adminToken;
    private User target;
    private Application app1;
    private Application app2;
    private Application app3;

    @BeforeEach
    void setUp() {
        applicationRepository.deleteAll();
        organizationRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Organization org = organizationRepository.save(
                Organization.builder().name("Org").description("o").build());

        app1 = saveApp("App One", org.getId());
        app2 = saveApp("App Two", org.getId());
        app3 = saveApp("App Three", org.getId());

        Role admin = roleRepository.save(Role.builder()
                .name("SuperAdmin").permissions(List.of("super_admin")).build());
        User adminUser = userRepository.save(User.builder()
                .username("admin").email("admin@test.com").password("n/a")
                .loginOption(LoginOption.NATIVE).roleIds(List.of(admin.getId()))
                .isInternal(true).createdAt(LocalDateTime.now()).failedLoginAttempts(0)
                .build());
        adminToken = jwtService.generateToken(adminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin")));

        target = userRepository.save(User.builder()
                .username("ext-user").email("ext@client.com").firstName("Ext").lastName("User")
                .password("n/a").loginOption(LoginOption.SAML2)
                .isInternal(false).organizationId(org.getId())
                .createdAt(LocalDateTime.now()).failedLoginAttempts(0)
                .build());
    }

    @Test
    void syncAssignments_addsUserToRequestedApplications() throws Exception {
        String body = """
            {"assignments":[
              {"applicationId":"%s","accessLevel":"WRITE"},
              {"applicationId":"%s","accessLevel":"READ"}
            ]}""".formatted(app1.getId(), app2.getId());

        mockMvc.perform(put("/api/v1/users/" + target.getId() + "/application-assignments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));

        assertThat(assignmentLevel(app1, target.getId())).isEqualTo("WRITE");
        assertThat(assignmentLevel(app2, target.getId())).isEqualTo("READ");
        assertThat(assignmentLevel(app3, target.getId())).isNull();
    }

    @Test
    void syncAssignments_updatesAndRemovesToMatchRequest() throws Exception {
        // pre-assign to app1 (WRITE) and app3 (READ)
        assign(app1, target, "WRITE");
        assign(app3, target, "READ");

        // request: app1 -> READ (downgrade), app2 -> WRITE (add); app3 dropped
        String body = """
            {"assignments":[
              {"applicationId":"%s","accessLevel":"READ"},
              {"applicationId":"%s","accessLevel":"WRITE"}
            ]}""".formatted(app1.getId(), app2.getId());

        mockMvc.perform(put("/api/v1/users/" + target.getId() + "/application-assignments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));

        assertThat(assignmentLevel(app1, target.getId())).isEqualTo("READ");
        assertThat(assignmentLevel(app2, target.getId())).isEqualTo("WRITE");
        assertThat(assignmentLevel(app3, target.getId())).isNull();
    }

    @Test
    void syncAssignments_emptyListClearsAllAssignments() throws Exception {
        assign(app1, target, "WRITE");
        assign(app2, target, "READ");

        mockMvc.perform(put("/api/v1/users/" + target.getId() + "/application-assignments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignments\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        assertThat(assignmentLevel(app1, target.getId())).isNull();
        assertThat(assignmentLevel(app2, target.getId())).isNull();
    }

    @Test
    void getAssignments_returnsCurrentAssignments() throws Exception {
        assign(app2, target, "WRITE");

        mockMvc.perform(get("/api/v1/users/" + target.getId() + "/application-assignments")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].applicationId").value(app2.getId()))
                .andExpect(jsonPath("$.data[0].accessLevel").value("WRITE"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Application saveApp(String name, String orgId) {
        return applicationRepository.save(Application.builder()
                .name(name).description("d").organizationId(orgId)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
    }

    private void assign(Application app, User user, String level) {
        Application fresh = applicationRepository.findById(app.getId()).orElseThrow();
        fresh.getAssignedUsers().add(AssignedUser.builder()
                .userId(user.getId()).displayName("x").email(user.getEmail())
                .accessLevel(level).build());
        applicationRepository.save(fresh);
    }

    private String assignmentLevel(Application app, String userId) {
        return applicationRepository.findById(app.getId()).orElseThrow()
                .getAssignedUsers().stream()
                .filter(u -> u.getUserId().equals(userId))
                .map(AssignedUser::getAccessLevel)
                .findFirst().orElse(null);
    }
}
