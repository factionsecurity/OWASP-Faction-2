package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.*;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.EntityFieldConfigRepository;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrganizationControllerTest extends TestContainersConfig {

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
    private ApplicationRepository applicationRepository;

    @Autowired
    private EntityFieldConfigRepository entityFieldConfigRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private Role superAdminRole;
    private User superAdminUser;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        roleRepository.deleteAll();
        organizationRepository.deleteAll();
        applicationRepository.deleteAll();
        entityFieldConfigRepository.deleteAll();

        // Create SuperAdmin role
        superAdminRole = Role.builder()
                .name("SuperAdmin")
                .description("Super Administrator with full access")
                .permissions(List.of("super_admin"))
                .build();
        superAdminRole = roleRepository.save(superAdminRole);

        // Create SuperAdmin user
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
    }

    // ==================== CREATE ORGANIZATION TESTS ====================

    @Test
    void createOrganization_AsSuperAdmin_ReturnsCreatedOrganization() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        String requestBody = """
                {
                    "name": "Acme Corporation",
                    "description": "A leading technology company"
                }
                """;

        mockMvc.perform(post("/api/v1/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Organization created successfully"))
                .andExpect(jsonPath("$.data.name").value("Acme Corporation"))
                .andExpect(jsonPath("$.data.description").value("A leading technology company"))
                .andExpect(jsonPath("$.data.id").exists());

        // Verify organization was created in database
        assertThat(organizationRepository.findByName("Acme Corporation")).isPresent();
    }

    @Test
    void createOrganization_WithDuplicateName_ReturnsBadRequest() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create first organization
        Organization existingOrg = Organization.builder()
                .name("Duplicate Org")
                .description("First organization")
                .build();
        organizationRepository.save(existingOrg);

        // Try to create with same name
        String requestBody = """
                {
                    "name": "Duplicate Org",
                    "description": "Second organization with duplicate name"
                }
                """;

        mockMvc.perform(post("/api/v1/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Organization with name 'Duplicate Org' already exists"));
    }

    @Test
    void createOrganization_WithMissingName_ReturnsBadRequest() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        String requestBody = """
                {
                    "description": "Organization without name"
                }
                """;

        mockMvc.perform(post("/api/v1/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    // ==================== UPDATE ORGANIZATION TESTS ====================

    @Test
    void updateOrganization_AsSuperAdmin_UpdatesSuccessfully() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create organization to update
        Organization org = Organization.builder()
                .name("Old Name")
                .description("Old description")
                .build();
        org = organizationRepository.save(org);

        String requestBody = """
                {
                    "name": "Updated Name",
                    "description": "Updated description"
                }
                """;

        mockMvc.perform(put("/api/v1/organizations/" + org.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Organization updated successfully"))
                .andExpect(jsonPath("$.data.name").value("Updated Name"))
                .andExpect(jsonPath("$.data.description").value("Updated description"));

        // Verify changes in database
        Organization updatedOrg = organizationRepository.findById(org.getId()).orElseThrow();
        assertThat(updatedOrg.getName()).isEqualTo("Updated Name");
        assertThat(updatedOrg.getDescription()).isEqualTo("Updated description");
    }

    @Test
    void updateOrganization_WithDuplicateName_ReturnsBadRequest() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create two organizations
        Organization org1 = Organization.builder()
                .name("Organization One")
                .description("First org")
                .build();
        organizationRepository.save(org1);

        Organization org2 = Organization.builder()
                .name("Organization Two")
                .description("Second org")
                .build();
        org2 = organizationRepository.save(org2);

        // Try to update org2 with org1's name
        String requestBody = """
                {
                    "name": "Organization One",
                    "description": "Trying to use duplicate name"
                }
                """;

        mockMvc.perform(put("/api/v1/organizations/" + org2.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Organization with name 'Organization One' already exists"));
    }

    @Test
    void updateOrganization_WithNonExistentId_ReturnsNotFound() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        String requestBody = """
                {
                    "name": "Some Name",
                    "description": "Some description"
                }
                """;

        mockMvc.perform(put("/api/v1/organizations/nonexistent-id")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Organization not found with id: nonexistent-id"));
    }

    // ==================== DELETE ORGANIZATION TESTS ====================

    @Test
    void deleteOrganization_WithoutApplications_DeletesSuccessfully() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create organization without applications
        Organization org = Organization.builder()
                .name("Org To Delete")
                .description("This org will be deleted")
                .build();
        org = organizationRepository.save(org);

        mockMvc.perform(delete("/api/v1/organizations/" + org.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Organization deleted successfully"));

        // Verify organization was deleted
        assertThat(organizationRepository.findById(org.getId())).isEmpty();
    }

    @Test
    void deleteOrganization_WithApplications_ReturnsBadRequest() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create organization
        Organization org = Organization.builder()
                .name("Org With Apps")
                .description("Organization with applications")
                .build();
        org = organizationRepository.save(org);

        // Create applications assigned to this organization
        Application app1 = Application.builder()
                .name("App 1")
                .ownerName("Owner")
                .ownerEmail("owner@example.com")
                .organizationId(org.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .createdAt(LocalDateTime.now())
                .build();
        applicationRepository.save(app1);

        Application app2 = Application.builder()
                .name("App 2")
                .ownerName("Owner")
                .ownerEmail("owner@example.com")
                .organizationId(org.getId())
                .applicationType("Mobile Application")
                .assessmentFrequency("Yearly")
                .createdAt(LocalDateTime.now())
                .build();
        applicationRepository.save(app2);

        // Try to delete organization with applications
        mockMvc.perform(delete("/api/v1/organizations/" + org.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Cannot delete organization with 2 assigned application(s). Please remove or reassign applications first."));

        // Verify organization still exists
        assertThat(organizationRepository.findById(org.getId())).isPresent();
    }

    @Test
    void deleteOrganization_AfterRemovingApplications_DeletesSuccessfully() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create organization
        Organization org = Organization.builder()
                .name("Org With App")
                .description("Organization with one application")
                .build();
        org = organizationRepository.save(org);

        // Create application assigned to this organization
        Application app = Application.builder()
                .name("App To Remove")
                .ownerName("Owner")
                .ownerEmail("owner@example.com")
                .organizationId(org.getId())
                .applicationType("API")
                .assessmentFrequency("Ad Hoc")
                .createdAt(LocalDateTime.now())
                .build();
        app = applicationRepository.save(app);

        // First attempt should fail
        mockMvc.perform(delete("/api/v1/organizations/" + org.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        // Remove the application
        applicationRepository.deleteById(app.getId());

        // Now deletion should succeed
        mockMvc.perform(delete("/api/v1/organizations/" + org.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Organization deleted successfully"));

        // Verify organization was deleted
        assertThat(organizationRepository.findById(org.getId())).isEmpty();
    }

    @Test
    void deleteOrganization_WithNonExistentId_ReturnsNotFound() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        mockMvc.perform(delete("/api/v1/organizations/nonexistent-id")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Organization not found with id: nonexistent-id"));
    }

    // ==================== GET ORGANIZATION TESTS ====================

    @Test
    void getAllOrganizations_AsSuperAdmin_ReturnsAllOrganizations() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create multiple organizations
        Organization org1 = Organization.builder()
                .name("Org 1")
                .description("First organization")
                .build();
        organizationRepository.save(org1);

        Organization org2 = Organization.builder()
                .name("Org 2")
                .description("Second organization")
                .build();
        organizationRepository.save(org2);

        mockMvc.perform(get("/api/v1/organizations")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.pagination.totalElements").value(2));
    }

    @Test
    void getOrganizationById_AsSuperAdmin_ReturnsOrganization() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        Organization org = Organization.builder()
                .name("Test Org")
                .description("Test organization")
                .build();
        org = organizationRepository.save(org);

        mockMvc.perform(get("/api/v1/organizations/" + org.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(org.getId()))
                .andExpect(jsonPath("$.data.name").value("Test Org"))
                .andExpect(jsonPath("$.data.description").value("Test organization"));
    }

    @Test
    void getOrganizationById_WithNonExistentId_ReturnsNotFound() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        mockMvc.perform(get("/api/v1/organizations/nonexistent-id")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Organization not found with id: nonexistent-id"));
    }

    // ==================== APPLICATION ASSIGNMENT TESTS ====================

    @Test
    void assignApplicationToOrganization_CreatesRelationship() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create organization
        Organization org = Organization.builder()
                .name("Tech Company")
                .description("Technology company")
                .build();
        org = organizationRepository.save(org);

        // Create application for this organization
        String appRequestBody = String.format("""
                {
                    "name": "Web Portal",
                    "description": "Customer portal",
                    "ownerName": "John Doe",
                    "ownerEmail": "john@example.com",
                    "organizationId": "%s",
                    "applicationType": "WEB",
                    "assessmentFrequency": "QUARTERLY",
                    "technologies": ["React", "Node.js"]
                }
                """, org.getId());

        mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(appRequestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.organizationId").value(org.getId()));

        // Verify application is assigned to organization
        List<Application> apps = applicationRepository.findByOrganizationId(org.getId());
        assertThat(apps).hasSize(1);
        assertThat(apps.get(0).getName()).isEqualTo("Web Portal");
    }

    // ==================== FIELD VALUES TESTS ====================

    @Test
    void createOrganization_WithFieldValues_StoresAndReturnsFieldValues() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        String fieldId = UUID.randomUUID().toString();
        EntityFieldConfig config = EntityFieldConfig.builder()
                .scope(FieldScope.ORGANIZATION)
                .fieldDefinitions(List.of(
                        UserDefinedField.builder()
                                .id(fieldId)
                                .variableName("industry")
                                .displayName("Industry")
                                .fieldType(FieldType.DROPDOWN)
                                .fieldScope(FieldScope.ORGANIZATION)
                                .displayOrder(1)
                                .build()
                ))
                .build();
        entityFieldConfigRepository.save(config);

        String requestBody = String.format("""
                {
                    "name": "Org With Fields",
                    "description": "Has custom fields",
                    "fieldValues": {
                        "%s": "Finance"
                    }
                }
                """, fieldId);

        mockMvc.perform(post("/api/v1/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fieldValues." + fieldId).value("Finance"))
                .andExpect(jsonPath("$.data.fieldDefinitions").isArray())
                .andExpect(jsonPath("$.data.fieldDefinitions.length()").value(1))
                .andExpect(jsonPath("$.data.fieldDefinitions[0].variableName").value("industry"));
    }

    @Test
    void updateOrganization_WithFieldValues_UpdatesFieldValues() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        String fieldId = UUID.randomUUID().toString();
        EntityFieldConfig config = EntityFieldConfig.builder()
                .scope(FieldScope.ORGANIZATION)
                .fieldDefinitions(List.of(
                        UserDefinedField.builder()
                                .id(fieldId)
                                .variableName("region")
                                .displayName("Region")
                                .fieldType(FieldType.STRING)
                                .fieldScope(FieldScope.ORGANIZATION)
                                .displayOrder(1)
                                .build()
                ))
                .build();
        entityFieldConfigRepository.save(config);

        Organization org = Organization.builder()
                .name("Org To Update Fields")
                .description("Test org")
                .build();
        org = organizationRepository.save(org);

        String requestBody = String.format("""
                {
                    "name": "Org To Update Fields",
                    "description": "Updated",
                    "fieldValues": {
                        "%s": "EMEA"
                    }
                }
                """, fieldId);

        mockMvc.perform(put("/api/v1/organizations/" + org.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fieldValues." + fieldId).value("EMEA"));
    }

    @Test
    void getOrganizationById_ReturnsFieldDefinitionsAndValues() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        String fieldId = UUID.randomUUID().toString();
        EntityFieldConfig config = EntityFieldConfig.builder()
                .scope(FieldScope.ORGANIZATION)
                .fieldDefinitions(List.of(
                        UserDefinedField.builder()
                                .id(fieldId)
                                .variableName("contract_type")
                                .displayName("Contract Type")
                                .fieldType(FieldType.STRING)
                                .fieldScope(FieldScope.ORGANIZATION)
                                .displayOrder(1)
                                .build()
                ))
                .build();
        entityFieldConfigRepository.save(config);

        Organization org = Organization.builder()
                .name("Org With Fields")
                .description("Has field values")
                .fieldValues(new java.util.HashMap<>(java.util.Map.of(fieldId, "Enterprise")))
                .build();
        org = organizationRepository.save(org);

        mockMvc.perform(get("/api/v1/organizations/" + org.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fieldDefinitions.length()").value(1))
                .andExpect(jsonPath("$.data.fieldDefinitions[0].variableName").value("contract_type"))
                .andExpect(jsonPath("$.data.fieldValues." + fieldId).value("Enterprise"));
    }

    // ==================== ASSIGNED USER TESTS ====================

    @Test
    void assignUser_ToOrganization_AsSuperAdmin_ReturnsCreatedAssignment() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        User targetUser = User.builder()
                .username("orgtargetuser")
                .email("orgtarget@example.com")
                .firstName("OrgTarget")
                .lastName("User")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        targetUser = userRepository.save(targetUser);

        Organization org = Organization.builder()
                .name("Org For Assignment")
                .description("Test org")
                .build();
        org = organizationRepository.save(org);

        String requestBody = String.format("""
                {"userId": "%s", "accessLevel": "WRITE"}
                """, targetUser.getId());

        mockMvc.perform(post("/api/v1/organizations/" + org.getId() + "/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(targetUser.getId()))
                .andExpect(jsonPath("$.data.email").value("orgtarget@example.com"))
                .andExpect(jsonPath("$.data.accessLevel").value("WRITE"));
    }

    @Test
    void getAllOrganizations_AsOwnedUser_ReturnsOnlyAssignedOrgs() throws Exception {
        // Owned scope: the user's home organization is the org-level grant
        Organization assignedOrg = organizationRepository.save(Organization.builder()
                .name("Assigned Org")
                .description("User's home organization")
                .build());

        User ownedUser = User.builder()
                .username("orgowneduser")
                .email("orgowned@example.com")
                .firstName("OrgOwned")
                .lastName("User")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .isInternal(false)
                .organizationId(assignedOrg.getId())
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        ownedUser = userRepository.save(ownedUser);

        Organization unassignedOrg = Organization.builder()
                .name("Unassigned Org")
                .description("User is NOT assigned here")
                .build();
        organizationRepository.save(unassignedOrg);

        String token = jwtService.generateToken(
                ownedUser.getId(),
                List.of(new SimpleGrantedAuthority("organizations:read:owned"))
        );

        mockMvc.perform(get("/api/v1/organizations")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Assigned Org"));
    }

    @Test
    void getOrganizationById_AsOwnedUser_NotAssigned_ReturnsNotFound() throws Exception {
        User ownedUser = User.builder()
                .username("orgowneduser2")
                .email("orgowned2@example.com")
                .firstName("OrgOwned2")
                .lastName("User2")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        ownedUser = userRepository.save(ownedUser);

        Organization org = Organization.builder()
                .name("Not Assigned Org")
                .description("Test org")
                .build();
        org = organizationRepository.save(org);

        String token = jwtService.generateToken(
                ownedUser.getId(),
                List.of(new SimpleGrantedAuthority("organizations:read:owned"))
        );

        mockMvc.perform(get("/api/v1/organizations/" + org.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateOrganization_AsOwnedUserWithWriteAccess_ReturnsOk() throws Exception {
        User ownedUser = User.builder()
                .username("orgwriter")
                .email("orgwriter@example.com")
                .firstName("OrgWriter")
                .lastName("User")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        ownedUser = userRepository.save(ownedUser);

        AssignedUser assignedUser = AssignedUser.builder()
                .userId(ownedUser.getId())
                .displayName("OrgWriter User")
                .email("orgwriter@example.com")
                .accessLevel("WRITE")
                .build();

        Organization org = Organization.builder()
                .name("Writable Org")
                .description("Test org")
                .assignedUsers(new ArrayList<>(List.of(assignedUser)))
                .build();
        org = organizationRepository.save(org);

        String token = jwtService.generateToken(
                ownedUser.getUsername(),
                List.of(new SimpleGrantedAuthority("organizations:read:owned"))
        );

        mockMvc.perform(put("/api/v1/organizations/" + org.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\": \"Writable Org\", \"description\": \"Updated\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void updateOrganization_AsOwnedUserWithReadAccess_ReturnsForbidden() throws Exception {
        User ownedUser = User.builder()
                .username("orgreader")
                .email("orgreader@example.com")
                .firstName("OrgReader")
                .lastName("User")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        ownedUser = userRepository.save(ownedUser);

        AssignedUser assignedUser = AssignedUser.builder()
                .userId(ownedUser.getId())
                .displayName("OrgReader User")
                .email("orgreader@example.com")
                .accessLevel("READ")
                .build();

        Organization org = Organization.builder()
                .name("Read Only Org")
                .description("Test org")
                .assignedUsers(new ArrayList<>(List.of(assignedUser)))
                .build();
        org = organizationRepository.save(org);

        String token = jwtService.generateToken(
                ownedUser.getUsername(),
                List.of(new SimpleGrantedAuthority("organizations:read:owned"))
        );

        mockMvc.perform(put("/api/v1/organizations/" + org.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\": \"Read Only Org\", \"description\": \"Attempted update\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void removeAssignedUser_FromOrganization_AsSuperAdmin_ReturnsOk() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        User targetUser = User.builder()
                .username("orgremoveuser")
                .email("orgremove@example.com")
                .firstName("OrgRemove")
                .lastName("User")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        targetUser = userRepository.save(targetUser);

        AssignedUser assignedUser = AssignedUser.builder()
                .userId(targetUser.getId())
                .displayName("OrgRemove User")
                .email("orgremove@example.com")
                .accessLevel("WRITE")
                .build();

        Organization org = Organization.builder()
                .name("Org With User To Remove")
                .description("Test org")
                .assignedUsers(new ArrayList<>(List.of(assignedUser)))
                .build();
        org = organizationRepository.save(org);

        mockMvc.perform(delete("/api/v1/organizations/" + org.getId() + "/users/" + targetUser.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Organization updated = organizationRepository.findById(org.getId()).orElseThrow();
        assertThat(updated.getAssignedUsers()).isEmpty();
    }

    @Test
    void getApplicationsByOrganization_ReturnsOnlyAssignedApplications() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create two organizations
        Organization org1 = Organization.builder()
                .name("Org 1")
                .description("First org")
                .build();
        org1 = organizationRepository.save(org1);

        Organization org2 = Organization.builder()
                .name("Org 2")
                .description("Second org")
                .build();
        org2 = organizationRepository.save(org2);

        // Create applications for org1
        Application app1 = Application.builder()
                .name("Org1 App1")
                .ownerName("Owner")
                .ownerEmail("owner@example.com")
                .organizationId(org1.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .createdAt(LocalDateTime.now())
                .build();
        applicationRepository.save(app1);

        Application app2 = Application.builder()
                .name("Org1 App2")
                .ownerName("Owner")
                .ownerEmail("owner@example.com")
                .organizationId(org1.getId())
                .applicationType("Mobile Application")
                .assessmentFrequency("Yearly")
                .createdAt(LocalDateTime.now())
                .build();
        applicationRepository.save(app2);

        // Create application for org2
        Application app3 = Application.builder()
                .name("Org2 App1")
                .ownerName("Owner")
                .ownerEmail("owner@example.com")
                .organizationId(org2.getId())
                .applicationType("API")
                .assessmentFrequency("Ad Hoc")
                .createdAt(LocalDateTime.now())
                .build();
        applicationRepository.save(app3);

        // Get applications for org1
        mockMvc.perform(get("/api/v1/applications/organization/" + org1.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2));

        // Get applications for org2
        mockMvc.perform(get("/api/v1/applications/organization/" + org2.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1));
    }
}
