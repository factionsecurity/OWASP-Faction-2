package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.AssessmentType;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
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

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssessmentTypeControllerTest extends TestContainersConfig {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AssessmentTypeRepository assessmentTypeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private Role superAdminRole;
    private Role assessmentManagerRole;
    private Role pentesterRole;
    private User superAdminUser;
    private User assessmentManagerUser;
    private User pentesterUser;
    private User noPermissionsUser;

    @BeforeEach
    void setUp() {
        assessmentTypeRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        // Create SuperAdmin role
        superAdminRole = Role.builder()
                .name("SuperAdmin")
                .description("Super Administrator with full access")
                .permissions(List.of("super_admin"))
                .build();
        superAdminRole = roleRepository.save(superAdminRole);

        // Create AssessmentManager role with all assessment permissions
        assessmentManagerRole = Role.builder()
                .name("AssessmentManager")
                .description("Assessment Manager with all assessment permissions")
                .permissions(List.of(
                        "assessments:create:all",
                        "assessments:edit:all",
                        "assessments:delete:all"
                ))
                .build();
        assessmentManagerRole = roleRepository.save(assessmentManagerRole);

        // Create Pentester role with no assessment type permissions
        pentesterRole = Role.builder()
                .name("Pentester")
                .description("Pentester with limited permissions")
                .permissions(List.of(
                        "assessments:read:team",
                        "vulnerabilities:edit:assessment"
                ))
                .build();
        pentesterRole = roleRepository.save(pentesterRole);

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

        // Create AssessmentManager user
        assessmentManagerUser = User.builder()
                .username("assessmentmanager")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(assessmentManagerRole.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        assessmentManagerUser = userRepository.save(assessmentManagerUser);

        // Create Pentester user (no assessment type permissions)
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

        // Create user with no permissions
        noPermissionsUser = User.builder()
                .username("nopermissions")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        noPermissionsUser = userRepository.save(noPermissionsUser);
    }

    // ============== GET ALL TESTS ==============

    @Test
    void getAllAssessmentTypes_AsSuperAdmin_ReturnsAllTypes() throws Exception {
        // Create test data
        createTestAssessmentType("Web App Pentest", "Web application testing");
        createTestAssessmentType("Mobile App Pentest", "Mobile application testing");

        String token = generateSuperAdminToken();

        mockMvc.perform(get("/api/v1/assessment-types")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Assessment types retrieved successfully"))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].name", containsInAnyOrder("Web App Pentest", "Mobile App Pentest")));
    }

    @Test
    void getAllAssessmentTypes_AsAuthenticatedUser_ReturnsAllTypes() throws Exception {
        createTestAssessmentType("Network Assessment", "Network testing");

        String token = generatePentesterToken();

        mockMvc.perform(get("/api/v1/assessment-types")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)));
    }

    @Test
    void getAllAssessmentTypes_WithPagination_ReturnsPagedResults() throws Exception {
        // Create 15 assessment types
        for (int i = 1; i <= 15; i++) {
            createTestAssessmentType("Type " + i, "Description " + i);
        }

        String token = generateSuperAdminToken();

        mockMvc.perform(get("/api/v1/assessment-types")
                        .param("page", "0")
                        .param("size", "10")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(10)))
                .andExpect(jsonPath("$.pagination.totalElements").value(15))
                .andExpect(jsonPath("$.pagination.totalPages").value(2));
    }

    @Test
    void getAllAssessmentTypes_WithSorting_ReturnsSortedResults() throws Exception {
        createTestAssessmentType("Zebra Test", "Description");
        createTestAssessmentType("Alpha Test", "Description");
        createTestAssessmentType("Beta Test", "Description");

        String token = generateSuperAdminToken();

        mockMvc.perform(get("/api/v1/assessment-types")
                        .param("sort", "name,asc")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].name").value("Alpha Test"))
                .andExpect(jsonPath("$.data[1].name").value("Beta Test"))
                .andExpect(jsonPath("$.data[2].name").value("Zebra Test"));
    }

    @Test
    void getAllAssessmentTypes_WithoutAuth_ReturnsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/assessment-types"))
                .andExpect(status().isForbidden());
    }

    // ============== GET BY ID TESTS ==============

    @Test
    void getAssessmentTypeById_AsSuperAdmin_ReturnsType() throws Exception {
        AssessmentType savedType = createTestAssessmentType("RedTeam Assessment", "Red team testing");

        String token = generateSuperAdminToken();

        mockMvc.perform(get("/api/v1/assessment-types/" + savedType.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(savedType.getId()))
                .andExpect(jsonPath("$.data.name").value("RedTeam Assessment"))
                .andExpect(jsonPath("$.data.description").value("Red team testing"))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.updatedAt").exists());
    }

    @Test
    void getAssessmentTypeById_AsAuthenticatedUser_ReturnsType() throws Exception {
        AssessmentType savedType = createTestAssessmentType("API Security Test", "API testing");

        String token = generatePentesterToken();

        mockMvc.perform(get("/api/v1/assessment-types/" + savedType.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("API Security Test"));
    }

    @Test
    void getAssessmentTypeById_WithInvalidId_ReturnsNotFound() throws Exception {
        String token = generateSuperAdminToken();

        mockMvc.perform(get("/api/v1/assessment-types/nonexistent-id")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Assessment type not found with id: nonexistent-id"));
    }

    @Test
    void getAssessmentTypeById_WithoutAuth_ReturnsForbidden() throws Exception {
        AssessmentType savedType = createTestAssessmentType("Test Type", "Description");

        mockMvc.perform(get("/api/v1/assessment-types/" + savedType.getId()))
                .andExpect(status().isForbidden());
    }

    // ============== CREATE TESTS ==============

    @Test
    void createAssessmentType_AsSuperAdmin_CreatesType() throws Exception {
        String token = generateSuperAdminToken();

        mockMvc.perform(post("/api/v1/assessment-types")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cloud Security Assessment\",\"description\":\"Cloud infrastructure security testing\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Assessment type created successfully"))
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.name").value("Cloud Security Assessment"))
                .andExpect(jsonPath("$.data.description").value("Cloud infrastructure security testing"))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.updatedAt").exists());
    }

    @Test
    void createAssessmentType_AsAssessmentManager_CreatesType() throws Exception {
        String token = generateAssessmentManagerToken();

        mockMvc.perform(post("/api/v1/assessment-types")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"IoT Security Assessment\",\"description\":\"IoT device security testing\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("IoT Security Assessment"));
    }

    @Test
    void createAssessmentType_WithOnlyCreatePermission_CreatesType() throws Exception {
        // Create user with only create permission
        Role createOnlyRole = Role.builder()
                .name("CreateOnly")
                .permissions(List.of("assessments:create:all"))
                .build();
        createOnlyRole = roleRepository.save(createOnlyRole);

        User createOnlyUser = User.builder()
                .username("createonly")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(createOnlyRole.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        createOnlyUser = userRepository.save(createOnlyUser);

        String token = jwtService.generateToken(
                createOnlyUser.getUsername(),
                List.of(new SimpleGrantedAuthority("assessments:create:all"))
        );

        mockMvc.perform(post("/api/v1/assessment-types")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Blockchain Security\",\"description\":\"Blockchain testing\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void createAssessmentType_AsPentester_ReturnsForbidden() throws Exception {
        String token = generatePentesterToken();

        mockMvc.perform(post("/api/v1/assessment-types")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Unauthorized Type\",\"description\":\"Should not be created\",\"active\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createAssessmentType_WithNoPermissions_ReturnsForbidden() throws Exception {
        String token = jwtService.generateToken(
                noPermissionsUser.getUsername(),
                List.of()
        );

        mockMvc.perform(post("/api/v1/assessment-types")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Unauthorized Type\",\"description\":\"Should not be created\",\"active\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createAssessmentType_WithDuplicateName_ReturnsBadRequest() throws Exception {
        createTestAssessmentType("Duplicate Type", "First one");

        String token = generateSuperAdminToken();

        mockMvc.perform(post("/api/v1/assessment-types")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Duplicate Type\",\"description\":\"Second one\",\"active\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Assessment type with name 'Duplicate Type' already exists"));
    }

    @Test
    void createAssessmentType_WithoutName_ReturnsBadRequest() throws Exception {
        String token = generateSuperAdminToken();

        mockMvc.perform(post("/api/v1/assessment-types")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Missing name\",\"active\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createAssessmentType_WithoutDescription_ReturnsBadRequest() throws Exception {
        String token = generateSuperAdminToken();

        mockMvc.perform(post("/api/v1/assessment-types")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Missing Description\",\"active\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createAssessmentType_WithoutAuth_ReturnsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/assessment-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test Type\",\"description\":\"Description\",\"active\":true}"))
                .andExpect(status().isForbidden());
    }

    // ============== UPDATE TESTS ==============

    @Test
    void updateAssessmentType_AsSuperAdmin_UpdatesType() throws Exception {
        AssessmentType savedType = createTestAssessmentType("Original Name", "Original description");

        String token = generateSuperAdminToken();

        mockMvc.perform(put("/api/v1/assessment-types/" + savedType.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated Name\",\"description\":\"Updated description\",\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Assessment type updated successfully"))
                .andExpect(jsonPath("$.data.id").value(savedType.getId()))
                .andExpect(jsonPath("$.data.name").value("Updated Name"))
                .andExpect(jsonPath("$.data.description").value("Updated description"))
                .andExpect(jsonPath("$.data.active").value(false));
    }

    @Test
    void updateAssessmentType_AsAssessmentManager_UpdatesType() throws Exception {
        AssessmentType savedType = createTestAssessmentType("Manager Test", "Original");

        String token = generateAssessmentManagerToken();

        mockMvc.perform(put("/api/v1/assessment-types/" + savedType.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Manager Updated\",\"description\":\"Updated by manager\",\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Manager Updated"));
    }

    @Test
    void updateAssessmentType_WithOnlyEditPermission_UpdatesType() throws Exception {
        AssessmentType savedType = createTestAssessmentType("Edit Test", "Original");

        // Create user with only edit permission
        Role editOnlyRole = Role.builder()
                .name("EditOnly")
                .permissions(List.of("assessments:edit:all"))
                .build();
        editOnlyRole = roleRepository.save(editOnlyRole);

        User editOnlyUser = User.builder()
                .username("editonly")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(editOnlyRole.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        editOnlyUser = userRepository.save(editOnlyUser);

        String token = jwtService.generateToken(
                editOnlyUser.getUsername(),
                List.of(new SimpleGrantedAuthority("assessments:edit:all"))
        );

        mockMvc.perform(put("/api/v1/assessment-types/" + savedType.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Edit Only Updated\",\"description\":\"Updated\",\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void updateAssessmentType_AsPentester_ReturnsForbidden() throws Exception {
        AssessmentType savedType = createTestAssessmentType("Pentest Type", "Original");

        String token = generatePentesterToken();

        mockMvc.perform(put("/api/v1/assessment-types/" + savedType.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Should Not Update\",\"description\":\"Forbidden\",\"active\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateAssessmentType_WithNoPermissions_ReturnsForbidden() throws Exception {
        AssessmentType savedType = createTestAssessmentType("Test Type", "Original");

        String token = jwtService.generateToken(
                noPermissionsUser.getUsername(),
                List.of()
        );

        mockMvc.perform(put("/api/v1/assessment-types/" + savedType.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Should Not Update\",\"description\":\"Forbidden\",\"active\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateAssessmentType_WithDuplicateName_ReturnsBadRequest() throws Exception {
        createTestAssessmentType("Existing Type", "First");
        AssessmentType typeToUpdate = createTestAssessmentType("Type To Update", "Second");

        String token = generateSuperAdminToken();

        mockMvc.perform(put("/api/v1/assessment-types/" + typeToUpdate.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Existing Type\",\"description\":\"Updated\",\"active\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Assessment type with name 'Existing Type' already exists"));
    }

    @Test
    void updateAssessmentType_WithInvalidId_ReturnsNotFound() throws Exception {
        String token = generateSuperAdminToken();

        mockMvc.perform(put("/api/v1/assessment-types/nonexistent-id")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\",\"description\":\"Updated\",\"active\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Assessment type not found with id: nonexistent-id"));
    }

    @Test
    void updateAssessmentType_WithoutAuth_ReturnsForbidden() throws Exception {
        AssessmentType savedType = createTestAssessmentType("Test Type", "Original");

        mockMvc.perform(put("/api/v1/assessment-types/" + savedType.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\",\"description\":\"Updated\",\"active\":true}"))
                .andExpect(status().isForbidden());
    }

    // ============== DELETE TESTS ==============

    @Test
    void deleteAssessmentType_AsSuperAdmin_DeletesType() throws Exception {
        AssessmentType savedType = createTestAssessmentType("Type to Delete", "Will be deleted");

        String token = generateSuperAdminToken();

        mockMvc.perform(delete("/api/v1/assessment-types/" + savedType.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Assessment type deleted successfully"));

        // Verify it's deleted
        mockMvc.perform(get("/api/v1/assessment-types/" + savedType.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteAssessmentType_AsAssessmentManager_DeletesType() throws Exception {
        AssessmentType savedType = createTestAssessmentType("Manager Delete", "To be deleted");

        String token = generateAssessmentManagerToken();

        mockMvc.perform(delete("/api/v1/assessment-types/" + savedType.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Assessment type deleted successfully"));
    }

    @Test
    void deleteAssessmentType_WithOnlyDeletePermission_DeletesType() throws Exception {
        AssessmentType savedType = createTestAssessmentType("Delete Test", "To be deleted");

        // Create user with only delete permission
        Role deleteOnlyRole = Role.builder()
                .name("DeleteOnly")
                .permissions(List.of("assessments:delete:all"))
                .build();
        deleteOnlyRole = roleRepository.save(deleteOnlyRole);

        User deleteOnlyUser = User.builder()
                .username("deleteonly")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(deleteOnlyRole.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        deleteOnlyUser = userRepository.save(deleteOnlyUser);

        String token = jwtService.generateToken(
                deleteOnlyUser.getUsername(),
                List.of(new SimpleGrantedAuthority("assessments:delete:all"))
        );

        mockMvc.perform(delete("/api/v1/assessment-types/" + savedType.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void deleteAssessmentType_AsPentester_ReturnsForbidden() throws Exception {
        AssessmentType savedType = createTestAssessmentType("Pentest Type", "Should not delete");

        String token = generatePentesterToken();

        mockMvc.perform(delete("/api/v1/assessment-types/" + savedType.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteAssessmentType_WithNoPermissions_ReturnsForbidden() throws Exception {
        AssessmentType savedType = createTestAssessmentType("Test Type", "Should not delete");

        String token = jwtService.generateToken(
                noPermissionsUser.getUsername(),
                List.of()
        );

        mockMvc.perform(delete("/api/v1/assessment-types/" + savedType.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteAssessmentType_WithInvalidId_ReturnsNotFound() throws Exception {
        String token = generateSuperAdminToken();

        mockMvc.perform(delete("/api/v1/assessment-types/nonexistent-id")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Assessment type not found with id: nonexistent-id"));
    }

    @Test
    void deleteAssessmentType_WithoutAuth_ReturnsForbidden() throws Exception {
        AssessmentType savedType = createTestAssessmentType("Test Type", "Description");

        mockMvc.perform(delete("/api/v1/assessment-types/" + savedType.getId()))
                .andExpect(status().isForbidden());
    }

    // Note: Test for deactivate vs delete behavior will be implemented once
    // isAssessmentTypeInUse() is implemented with Assessment model

    // ============== HELPER METHODS ==============

    private AssessmentType createTestAssessmentType(String name, String description) {
        AssessmentType type = AssessmentType.builder()
                .name(name)
                .description(description)
                .active(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        return assessmentTypeRepository.save(type);
    }

    private String generateSuperAdminToken() {
        return jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );
    }

    private String generateAssessmentManagerToken() {
        return jwtService.generateToken(
                assessmentManagerUser.getUsername(),
                List.of(
                        new SimpleGrantedAuthority("assessments:create:all"),
                        new SimpleGrantedAuthority("assessments:edit:all"),
                        new SimpleGrantedAuthority("assessments:delete:all")
                )
        );
    }

    private String generatePentesterToken() {
        return jwtService.generateToken(
                pentesterUser.getUsername(),
                List.of(
                        new SimpleGrantedAuthority("assessments:read:team"),
                        new SimpleGrantedAuthority("vulnerabilities:edit:assessment")
                )
        );
    }
}
