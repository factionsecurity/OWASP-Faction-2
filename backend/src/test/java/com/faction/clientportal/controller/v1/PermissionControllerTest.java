package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PermissionControllerTest extends TestContainersConfig {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        // Generate a SuperAdmin JWT directly so the test does not depend on
        // cross-context bootstrap state (the shared Testcontainer DB is recreated
        // per Spring context, which can wipe the bootstrapped admin user).
        adminToken = jwtService.generateToken(
                "admin",
                List.of(new SimpleGrantedAuthority("super_admin"))
        );
    }

    @Test
    void getAllPermissions_WithRolesReadAll_ReturnsResources() throws Exception {
        // The permission catalog is role-building reference data, so roles:read:all now grants read
        // access (previously super_admin-only) — reachable by a read-only super-admin token.
        String token = jwtService.generateToken(
                "reader",
                List.of(new SimpleGrantedAuthority("roles:read:all")));

        mockMvc.perform(get("/api/v1/permissions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void getAllPermissions_WithAdminAuth_ReturnsAllResources() throws Exception {
        mockMvc.perform(get("/api/v1/permissions")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(17))) // 17 resource categories
                .andExpect(jsonPath("$.data[*].resource", containsInAnyOrder(
                        "APPLICATIONS", "ASSESSMENTS", "USERS", "ORGANIZATIONS",
                        "VULNERABILITIES", "REPORTING", "ROLES", "REPORT_TEMPLATES",
                        "VULNERABILITY_CATEGORIES", "DEFAULT_VULNERABILITIES", "CHECKLIST_TEMPLATES",
                        "SURVEY_TEMPLATES", "SYSTEM_CONFIG", "PEER_REVIEW", "API_KEYS",
                        "CAMPAIGNS", "MANAGER_DASHBOARD"
                )));
    }

    @Test
    void getAllPermissions_WithAdminAuth_VerifiesResponseStructure() throws Exception {
        mockMvc.perform(get("/api/v1/permissions")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].resource").exists())
                .andExpect(jsonPath("$.data[0].displayName").exists())
                .andExpect(jsonPath("$.data[0].description").exists())
                .andExpect(jsonPath("$.data[0].permissions").isArray())
                .andExpect(jsonPath("$.data[0].permissions[0].permission").exists())
                .andExpect(jsonPath("$.data[0].permissions[0].description").exists());
    }

    @Test
    void getAllPermissions_WithAdminAuth_ContainsApplicationPermissions() throws Exception {
        mockMvc.perform(get("/api/v1/permissions")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].permissions[*].permission",
                        hasItems(
                                "applications:read:all",
                                "applications:create:all",
                                "applications:edit:all",
                                "applications:delete:all",
                                "applications:read:owned",
                                "applications:create:owned"
                        )));
    }

    @Test
    void getAllPermissions_WithAdminAuth_ContainsAssessmentPermissions() throws Exception {
        mockMvc.perform(get("/api/v1/permissions")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].permissions[*].permission",
                        hasItems(
                                "assessments:read:team",
                                "assessments:edit:team",
                                "assessments:create:team",
                                "assessments:delete:team",
                                "assessments:read:all",
                                "assessments:edit:all",
                                "assessments:create:all",
                                "assessments:delete:all",
                                "assessments:read:assigned",
                                "assessments:edit:assigned",
                                "assessments:edit:self"
                        )));
    }

    @Test
    void getAllPermissions_WithAdminAuth_ContainsUserPermissions() throws Exception {
        mockMvc.perform(get("/api/v1/permissions")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].permissions[*].permission",
                        hasItems(
                                "users:read:team",
                                "users:edit:team",
                                "users:create:team",
                                "users:delete:team",
                                "users:read:all",
                                "users:edit:all",
                                "users:create:all",
                                "users:delete:all"
                        )));
    }

    @Test
    void getAllPermissions_WithAdminAuth_ContainsOrganizationPermissions() throws Exception {
        mockMvc.perform(get("/api/v1/permissions")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].permissions[*].permission",
                        hasItems(
                                "organizations:read:all",
                                "organizations:create:all",
                                "organizations:edit:all",
                                "organizations:delete:all",
                                "organizations:read:owned"
                        )));
    }

    @Test
    void getAllPermissions_WithAdminAuth_ContainsVulnerabilityPermissions() throws Exception {
        mockMvc.perform(get("/api/v1/permissions")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].permissions[*].permission",
                        hasItems(
                                "vulnerabilities:read:assessment",
                                "vulnerabilities:edit:assessment",
                                "vulnerabilities:create:assessment",
                                "vulnerabilities:delete:assessment",
                                "vulnerabilities:read:team",
                                "vulnerabilities:edit:team",
                                "vulnerabilities:create:team",
                                "vulnerabilities:delete:team",
                                "vulnerabilities:read:all",
                                "vulnerabilities:edit:all",
                                "vulnerabilities:create:all",
                                "vulnerabilities:delete:all"
                        )));
    }

    @Test
    void getAllPermissions_WithAdminAuth_ContainsReportingPermissions() throws Exception {
        mockMvc.perform(get("/api/v1/permissions")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].permissions[*].permission",
                        hasItems(
                                "reporting:create",
                                "reporting:download"
                        )));
    }

    @Test
    void getAllPermissions_WithAdminAuth_ContainsRolePermissions() throws Exception {
        mockMvc.perform(get("/api/v1/permissions")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].permissions[*].permission",
                        hasItems(
                                "roles:read:all",
                                "roles:edit:all",
                                "roles:create:all",
                                "roles:delete:all"
                        )));
    }

    @Test
    void getAllPermissions_WithoutAuth_ReturnsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/permissions"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAllPermissions_WithInvalidToken_ReturnsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/permissions")
                        .header("Authorization", "Bearer invalid.token.here"))
                .andExpect(status().isForbidden());
    }
}
