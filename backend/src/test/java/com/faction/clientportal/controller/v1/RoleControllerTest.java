package com.faction.clientportal.controller.v1;

import com.faction.clientportal.edition.EnterpriseOnly;
import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Organization;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoleControllerTest extends TestContainersConfig {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private Role superAdminRole;
    private Role pentesterRole;
    private User superAdminUser;
    private User pentesterUser;
    private User externalUser;
    private Organization testOrganization;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        roleRepository.deleteAll();
        organizationRepository.deleteAll();

        // Create roles
        superAdminRole = Role.builder()
                .name("SuperAdmin")
                .description("Super Administrator with full access")
                .permissions(List.of("super_admin"))
                .build();
        superAdminRole = roleRepository.save(superAdminRole);

        pentesterRole = Role.builder()
                .name("Pentester")
                .description("Penetration Tester with assessment permissions")
                .permissions(Arrays.asList(
                        "assessments:edit:assigned",
                        "assessments_details:read:team",
                        "vulnerabilities:edit:assessment",
                        "reports:edit:assessment",
                        "reports:download:team",
                        "applications:read:all",
                        "organizations:read:all",
                        "boilerplate:read:all",
                        "vulnerability_templates:read:all",
                        "bolierplate:edit:self",
                        "peerreview:create:assessment",
                        "retests:edit:assigned",
                        "retest_report:edit:assigned",
                        "calendar:read:team",
                        "calendar:edit:self",
                        "peerreview:read:team",
                        "peerreview:edit:team",
                        "assessments:read:team",
                        "apikeys:create:self"
                ))
                .build();
        pentesterRole = roleRepository.save(pentesterRole);

        // Create organization for external user
        testOrganization = Organization.builder()
                .name("Test Organization")
                .description("Organization for testing external users")
                .build();
        testOrganization = organizationRepository.save(testOrganization);

        // Create SuperAdmin user (internal)
        superAdminUser = User.builder()
                .username("superadmin")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(superAdminRole.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        superAdminUser = userRepository.save(superAdminUser);

        // Create Pentester user (internal)
        pentesterUser = User.builder()
                .username("pentester")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(pentesterRole.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        pentesterUser = userRepository.save(pentesterUser);

        // Create External user (no internal permissions)
        externalUser = User.builder()
                .username("externaluser")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.SAML2)
                .isInternal(false)
                .organizationId(testOrganization.getId())
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        externalUser = userRepository.save(externalUser);
    }

    @Test
    void getAllRoles_AsSuperAdmin_ReturnsAllRoles() throws Exception {
        // Generate JWT token for SuperAdmin
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        MvcResult result = mockMvc.perform(get("/api/v1/roles")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Roles retrieved successfully"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.pagination").exists())
                .andExpect(jsonPath("$.pagination.page").value(0))
                .andExpect(jsonPath("$.pagination.size").value(10))
                .andExpect(jsonPath("$.pagination.totalElements").value(2))
                .andExpect(jsonPath("$.pagination.totalPages").value(1))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).contains("SuperAdmin");
        assertThat(responseBody).contains("Pentester");
        assertThat(responseBody).contains("super_admin");
        assertThat(responseBody).contains("assessments:edit:assigned");
        assertThat(responseBody).contains("vulnerabilities:edit:assessment");
    }

    @Test
    void getAllRoles_WithRolesReadAll_ReturnsRoles() throws Exception {
        // roles:read:all now grants read access to the roles list (previously super_admin-only).
        // A read-only super-admin API key reaches this endpoint because the read-universe
        // expansion carries roles:read:all.
        String token = jwtService.generateToken(
                pentesterUser.getUsername(),
                List.of(new SimpleGrantedAuthority("roles:read:all")));

        mockMvc.perform(get("/api/v1/roles")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void getAllRoles_AsPentester_ReturnsForbidden() throws Exception {
        // Generate JWT token for Pentester
        String token = jwtService.generateToken(
                pentesterUser.getUsername(),
                pentesterRole.getPermissions().stream()
                        .map(SimpleGrantedAuthority::new)
                        .toList()
        );

        mockMvc.perform(get("/api/v1/roles")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAllRoles_AsExternalUser_ReturnsForbidden() throws Exception {
        // Generate JWT token for External user (no permissions)
        String token = jwtService.generateToken(
                externalUser.getUsername(),
                List.of()  // External users have no internal permissions
        );

        mockMvc.perform(get("/api/v1/roles")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAllRoles_WithoutAuthentication_ReturnsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/roles"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAllRoles_WithInvalidToken_ReturnsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/roles")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAllRoles_VerifyAllPermissionsIncluded() throws Exception {
        // Generate JWT token for SuperAdmin
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        MvcResult result = mockMvc.perform(get("/api/v1/roles")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();

        // Verify SuperAdmin permissions
        assertThat(responseBody).contains("super_admin");

        // Verify Pentester permissions (all 19 permissions)
        List<String> expectedPermissions = Arrays.asList(
                "assessments:edit:assigned",
                "assessments_details:read:team",
                "vulnerabilities:edit:assessment",
                "reports:edit:assessment",
                "reports:download:team",
                "applications:read:all",
                "organizations:read:all",
                "boilerplate:read:all",
                "vulnerability_templates:read:all",
                "bolierplate:edit:self",
                "peerreview:create:assessment",
                "retests:edit:assigned",
                "retest_report:edit:assigned",
                "calendar:read:team",
                "calendar:edit:self",
                "peerreview:read:team",
                "peerreview:edit:team",
                "assessments:read:team",
                "apikeys:create:self"
        );

        for (String permission : expectedPermissions) {
            assertThat(responseBody).contains(permission);
        }
    }

    @Test
    void getAllRoles_WithPaginationParameters_ReturnsPaginatedResponse() throws Exception {
        // Generate JWT token for SuperAdmin
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        mockMvc.perform(get("/api/v1/roles")
                        .param("page", "0")
                        .param("size", "1")
                        .param("sort", "name,asc")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.pagination.page").value(0))
                .andExpect(jsonPath("$.pagination.size").value(1))
                .andExpect(jsonPath("$.pagination.totalElements").value(2))
                .andExpect(jsonPath("$.pagination.totalPages").value(2))
                .andExpect(jsonPath("$.pagination.first").value(true))
                .andExpect(jsonPath("$.pagination.last").value(false));
    }

    @Test
    void getAllRoles_SecondPage_ReturnsCorrectPaginationMetadata() throws Exception {
        // Generate JWT token for SuperAdmin
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        mockMvc.perform(get("/api/v1/roles")
                        .param("page", "1")
                        .param("size", "1")
                        .param("sort", "name,asc")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.size").value(1))
                .andExpect(jsonPath("$.pagination.totalElements").value(2))
                .andExpect(jsonPath("$.pagination.totalPages").value(2))
                .andExpect(jsonPath("$.pagination.first").value(false))
                .andExpect(jsonPath("$.pagination.last").value(true));
    }

    // ==================== CREATE ROLE TESTS ====================

    @Test

    @EnterpriseOnly
    void createRole_AsSuperAdmin_ReturnsCreatedRole() throws Exception {
        // Generate JWT token for SuperAdmin
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        String requestBody = """
                {
                    "name": "TestRole",
                    "description": "Test role for testing",
                    "permissions": ["test:read:all", "test:edit:self"]
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/v1/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Role created successfully"))
                .andExpect(jsonPath("$.data.name").value("TestRole"))
                .andExpect(jsonPath("$.data.description").value("Test role for testing"))
                .andExpect(jsonPath("$.data.permissions").isArray())
                .andExpect(jsonPath("$.data.permissions.length()").value(2))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).contains("test:read:all");
        assertThat(responseBody).contains("test:edit:self");
    }

    @Test

    @EnterpriseOnly
    void createRole_WithExternalFlag_PersistsAndReturnsIt() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        String requestBody = """
                {
                    "name": "Client Viewer",
                    "description": "External client role",
                    "permissions": ["applications:read:owned"],
                    "externalRole": true
                }
                """;

        String createdId = mockMvc.perform(post("/api/v1/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.externalRole").value(true))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        assertThat(roleRepository.findById(createdId).orElseThrow().isExternalRole()).isTrue();

        // Toggling it off via update clears the flag
        String updateBody = """
                {
                    "name": "Client Viewer",
                    "description": "External client role",
                    "permissions": ["applications:read:owned"],
                    "externalRole": false
                }
                """;
        mockMvc.perform(put("/api/v1/roles/" + createdId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.externalRole").value(false));

        assertThat(roleRepository.findById(createdId).orElseThrow().isExternalRole()).isFalse();
    }

    @Test

    @EnterpriseOnly
    void createRole_WithDuplicateName_ReturnsBadRequest() throws Exception {
        // Generate JWT token for SuperAdmin
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Try to create a role with the same name as an existing role
        String requestBody = """
                {
                    "name": "SuperAdmin",
                    "description": "Duplicate role",
                    "permissions": ["some:permission"]
                }
                """;

        mockMvc.perform(post("/api/v1/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Role with name 'SuperAdmin' already exists"));
    }

    @Test
    void createRole_WithInvalidData_ReturnsBadRequest() throws Exception {
        // Generate JWT token for SuperAdmin
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Missing required name field
        String requestBody = """
                {
                    "description": "Test role without name",
                    "permissions": ["test:read:all"]
                }
                """;

        mockMvc.perform(post("/api/v1/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    @Test

    @EnterpriseOnly
    void createRole_AsPentester_ReturnsForbidden() throws Exception {
        // Generate JWT token for Pentester
        String token = jwtService.generateToken(
                pentesterUser.getUsername(),
                pentesterRole.getPermissions().stream()
                        .map(SimpleGrantedAuthority::new)
                        .toList()
        );

        String requestBody = """
                {
                    "name": "TestRole",
                    "description": "Test role",
                    "permissions": ["test:read:all"]
                }
                """;

        mockMvc.perform(post("/api/v1/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isForbidden());
    }

    @Test

    @EnterpriseOnly
    void createRole_AsExternalUser_ReturnsForbidden() throws Exception {
        // Generate JWT token for External user
        String token = jwtService.generateToken(
                externalUser.getUsername(),
                List.of()
        );

        String requestBody = """
                {
                    "name": "TestRole",
                    "description": "Test role",
                    "permissions": ["test:read:all"]
                }
                """;

        mockMvc.perform(post("/api/v1/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isForbidden());
    }

    @Test
    void createRole_WithoutAuthentication_ReturnsForbidden() throws Exception {
        String requestBody = """
                {
                    "name": "TestRole",
                    "description": "Test role",
                    "permissions": ["test:read:all"]
                }
                """;

        mockMvc.perform(post("/api/v1/roles")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isForbidden());
    }

    // ==================== UPDATE ROLE TESTS ====================

    @Test

    @EnterpriseOnly
    void updateRole_AsSuperAdmin_ReturnsUpdatedRole() throws Exception {
        // Generate JWT token for SuperAdmin
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        String requestBody = """
                {
                    "name": "UpdatedPentester",
                    "description": "Updated description",
                    "permissions": ["new:permission:all"]
                }
                """;

        mockMvc.perform(put("/api/v1/roles/" + pentesterRole.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Role updated successfully"))
                .andExpect(jsonPath("$.data.name").value("UpdatedPentester"))
                .andExpect(jsonPath("$.data.description").value("Updated description"))
                .andExpect(jsonPath("$.data.permissions").isArray())
                .andExpect(jsonPath("$.data.permissions[0]").value("new:permission:all"));
    }

    @Test

    @EnterpriseOnly
    void updateRole_WithNonExistentId_ReturnsNotFound() throws Exception {
        // Generate JWT token for SuperAdmin
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        String requestBody = """
                {
                    "name": "TestRole",
                    "description": "Test description",
                    "permissions": ["test:permission"]
                }
                """;

        mockMvc.perform(put("/api/v1/roles/nonexistent-id")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Role not found with id: nonexistent-id"));
    }

    @Test

    @EnterpriseOnly
    void updateRole_WithDuplicateName_ReturnsBadRequest() throws Exception {
        // Generate JWT token for SuperAdmin
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Try to update Pentester role with SuperAdmin name
        String requestBody = """
                {
                    "name": "SuperAdmin",
                    "description": "Trying to use duplicate name",
                    "permissions": ["test:permission"]
                }
                """;

        mockMvc.perform(put("/api/v1/roles/" + pentesterRole.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Role with name 'SuperAdmin' already exists"));
    }

    @Test

    @EnterpriseOnly
    void updateRole_AsPentester_ReturnsForbidden() throws Exception {
        // Generate JWT token for Pentester
        String token = jwtService.generateToken(
                pentesterUser.getUsername(),
                pentesterRole.getPermissions().stream()
                        .map(SimpleGrantedAuthority::new)
                        .toList()
        );

        String requestBody = """
                {
                    "name": "UpdatedRole",
                    "description": "Updated",
                    "permissions": ["test:permission"]
                }
                """;

        mockMvc.perform(put("/api/v1/roles/" + pentesterRole.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isForbidden());
    }

    // ==================== DELETE ROLE TESTS ====================

    @Test

    @EnterpriseOnly
    void deleteRole_AsSuperAdmin_DeletesRole() throws Exception {
        // Create a test role to delete
        com.faction.clientportal.model.Role testRole = com.faction.clientportal.model.Role.builder()
                .name("RoleToDelete")
                .description("This role will be deleted")
                .permissions(List.of("test:permission"))
                .build();
        testRole = roleRepository.save(testRole);

        // Generate JWT token for SuperAdmin
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        mockMvc.perform(delete("/api/v1/roles/" + testRole.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Role deleted successfully"));

        // Verify role was deleted
        assertThat(roleRepository.findById(testRole.getId())).isEmpty();
    }

    @Test

    @EnterpriseOnly
    void deleteRole_SuperAdminRole_ReturnsBadRequest() throws Exception {
        // Generate JWT token for SuperAdmin
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        mockMvc.perform(delete("/api/v1/roles/" + superAdminRole.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Cannot delete default role: SuperAdmin"));

        // Verify role still exists
        assertThat(roleRepository.findById(superAdminRole.getId())).isPresent();
    }

    @Test

    @EnterpriseOnly
    void deleteRole_PentesterRole_ReturnsBadRequest() throws Exception {
        // Generate JWT token for SuperAdmin
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        mockMvc.perform(delete("/api/v1/roles/" + pentesterRole.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Cannot delete default role: Pentester"));

        // Verify role still exists
        assertThat(roleRepository.findById(pentesterRole.getId())).isPresent();
    }

    @Test

    @EnterpriseOnly
    void deleteRole_WithNonExistentId_ReturnsNotFound() throws Exception {
        // Generate JWT token for SuperAdmin
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        mockMvc.perform(delete("/api/v1/roles/nonexistent-id")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Role not found with id: nonexistent-id"));
    }

    @Test

    @EnterpriseOnly
    void deleteRole_AsPentester_ReturnsForbidden() throws Exception {
        // Create a test role
        com.faction.clientportal.model.Role testRole = com.faction.clientportal.model.Role.builder()
                .name("RoleToDelete")
                .description("This role will not be deleted")
                .permissions(List.of("test:permission"))
                .build();
        testRole = roleRepository.save(testRole);

        // Generate JWT token for Pentester
        String token = jwtService.generateToken(
                pentesterUser.getUsername(),
                pentesterRole.getPermissions().stream()
                        .map(SimpleGrantedAuthority::new)
                        .toList()
        );

        mockMvc.perform(delete("/api/v1/roles/" + testRole.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        // Verify role still exists
        assertThat(roleRepository.findById(testRole.getId())).isPresent();
    }

    @Test

    @EnterpriseOnly
    void deleteRole_AsExternalUser_ReturnsForbidden() throws Exception {
        // Create a test role
        com.faction.clientportal.model.Role testRole = com.faction.clientportal.model.Role.builder()
                .name("RoleToDelete")
                .description("This role will not be deleted")
                .permissions(List.of("test:permission"))
                .build();
        testRole = roleRepository.save(testRole);

        // Generate JWT token for External user
        String token = jwtService.generateToken(
                externalUser.getUsername(),
                List.of()
        );

        mockMvc.perform(delete("/api/v1/roles/" + testRole.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        // Verify role still exists
        assertThat(roleRepository.findById(testRole.getId())).isPresent();
    }

    @Test
    void deleteRole_WithoutAuthentication_ReturnsForbidden() throws Exception {
        // Create a test role
        com.faction.clientportal.model.Role testRole = com.faction.clientportal.model.Role.builder()
                .name("RoleToDelete")
                .description("This role will not be deleted")
                .permissions(List.of("test:permission"))
                .build();
        testRole = roleRepository.save(testRole);

        mockMvc.perform(delete("/api/v1/roles/" + testRole.getId()))
                .andExpect(status().isForbidden());

        // Verify role still exists
        assertThat(roleRepository.findById(testRole.getId())).isPresent();
    }
}
