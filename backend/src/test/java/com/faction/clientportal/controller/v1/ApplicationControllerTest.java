package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.*;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.EntityFieldConfigRepository;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.SubOrganizationRepository;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationControllerTest extends TestContainersConfig {

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
    private SubOrganizationRepository subOrganizationRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private Role superAdminRole;
    private User superAdminUser;
    private Organization testOrganization1;
    private Organization testOrganization2;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        roleRepository.deleteAll();
        applicationRepository.deleteAll();
        subOrganizationRepository.deleteAll();
        organizationRepository.deleteAll();
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

        // Create test organizations
        testOrganization1 = Organization.builder()
                .name("Test Organization 1")
                .description("First test organization")
                .build();
        testOrganization1 = organizationRepository.save(testOrganization1);

        testOrganization2 = Organization.builder()
                .name("Test Organization 2")
                .description("Second test organization")
                .build();
        testOrganization2 = organizationRepository.save(testOrganization2);
    }

    // ==================== CREATE APPLICATION TESTS ====================

    @Test
    void createApplication_AsSuperAdmin_ReturnsCreatedApplication() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        String requestBody = String.format("""
                {
                    "name": "Test Application",
                    "description": "Test application description",
                    "ownerName": "John Doe",
                    "ownerEmail": "john.doe@example.com",
                    "organizationId": "%s",
                    "applicationType": "Web Application",
                    "assessmentFrequency": "Quarterly",
                    "stakeHolders": [
                        {
                            "name": "Jane Smith",
                            "email": "jane.smith@example.com",
                            "role": "Project Manager"
                        }
                    ],
                    "technologies": ["Java", "Spring Boot", "MongoDB"]
                }
                """, testOrganization1.getId());

        MvcResult result = mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Application created successfully"))
                .andExpect(jsonPath("$.data.name").value("Test Application"))
                .andExpect(jsonPath("$.data.description").value("Test application description"))
                .andExpect(jsonPath("$.data.ownerName").value("John Doe"))
                .andExpect(jsonPath("$.data.ownerEmail").value("john.doe@example.com"))
                .andExpect(jsonPath("$.data.organizationId").value(testOrganization1.getId()))
                .andExpect(jsonPath("$.data.applicationType").value("Web Application"))
                .andExpect(jsonPath("$.data.assessmentFrequency").value("Quarterly"))
                .andExpect(jsonPath("$.data.stakeHolders").isArray())
                .andExpect(jsonPath("$.data.stakeHolders.length()").value(1))
                .andExpect(jsonPath("$.data.stakeHolders[0].name").value("Jane Smith"))
                .andExpect(jsonPath("$.data.technologies").isArray())
                .andExpect(jsonPath("$.data.technologies.length()").value(3))
                .andExpect(jsonPath("$.data.createdBy").value("superadmin"))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).contains("Java", "Spring Boot", "MongoDB");
    }

    @Test
    void listByOrganization_WithApplicationsReadAll_ReturnsList() throws Exception {
        // The per-organization application listing now accepts applications:read:all / :org
        // (previously super_admin-only) — reachable by a read-only super-admin token.
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("applications:read:all")));

        mockMvc.perform(get("/api/v1/applications/organization/" + testOrganization1.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }
                                           
    @Test
    void createApplication_WithLongRichTextDescription_PersistsBeyond255Chars() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Rich text descriptions are HTML and routinely exceed the old varchar(255) limit
        String longDescription = "<p>" + "This application handles payment processing. ".repeat(30) + "</p>";
        String requestBody = String.format("""
                {
                    "name": "Long Description App",
                    "description": "%s"
                }
                """, longDescription);

        mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.description").value(longDescription));
    }

    @Test
    void createApplication_AssignToOrganization_ApplicationBelongsToOrganization() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        String requestBody = String.format("""
                {
                    "name": "Org Assigned App",
                    "description": "Application assigned to org 1",
                    "ownerName": "Owner Name",
                    "ownerEmail": "owner@example.com",
                    "organizationId": "%s",
                    "applicationType": "Mobile Application",
                    "assessmentFrequency": "Yearly",
                    "technologies": ["React Native"]
                }
                """, testOrganization1.getId());

        mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.organizationId").value(testOrganization1.getId()));

        // Verify application is in organization
        mockMvc.perform(get("/api/v1/applications/organization/" + testOrganization1.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Org Assigned App"));
    }

    @Test
    void createApplication_WithInvalidOrganization_ReturnsNotFound() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        String requestBody = """
                {
                    "name": "Test App",
                    "description": "Test",
                    "ownerName": "Owner",
                    "ownerEmail": "owner@example.com",
                    "organizationId": "nonexistent-org-id",
                    "applicationType": "Web Application",
                    "assessmentFrequency": "Quarterly"
                }
                """;

        mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Organization not found with id: nonexistent-org-id"));
    }

    @Test
    void createApplication_WithDuplicateName_IsAllowed() throws Exception {
        // Application names are no longer unique — only appId enforces uniqueness
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create first application
        Application existingApp = Application.builder()
                .name("Duplicate App")
                .ownerName("Owner")
                .ownerEmail("owner@example.com")
                .organizationId(testOrganization1.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .createdAt(LocalDateTime.now())
                .build();
        applicationRepository.save(existingApp);

        // Create a second application with the same name
        String requestBody = String.format("""
                {
                    "name": "Duplicate App",
                    "description": "Test",
                    "ownerName": "Owner",
                    "ownerEmail": "owner@example.com",
                    "organizationId": "%s",
                    "applicationType": "Web Application",
                    "assessmentFrequency": "Quarterly"
                }
                """, testOrganization1.getId());

        mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Duplicate App"));
    }

    @Test
    void createApplication_WithDuplicateAppId_ReturnsBadRequest() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        Application existingApp = Application.builder()
                .name("Existing App")
                .appId("DUP-1")
                .ownerName("Owner")
                .ownerEmail("owner@example.com")
                .organizationId(testOrganization1.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .createdAt(LocalDateTime.now())
                .build();
        applicationRepository.save(existingApp);

        String requestBody = String.format("""
                {
                    "name": "Another App",
                    "appId": "DUP-1",
                    "description": "Test",
                    "organizationId": "%s",
                    "applicationType": "Web Application",
                    "assessmentFrequency": "Quarterly"
                }
                """, testOrganization1.getId());

        mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Application with appId 'DUP-1' already exists"));
    }

    // ==================== UPDATE APPLICATION TESTS ====================

    @Test
    void updateApplication_EditStakeHolders_UpdatesSuccessfully() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create application with initial stakeholder
        Application app = Application.builder()
                .name("App With Stakeholders")
                .ownerName("Owner")
                .ownerEmail("owner@example.com")
                .organizationId(testOrganization1.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .stakeHolders(List.of(
                        Stakeholder.builder()
                                .name("Initial Stakeholder")
                                .email("initial@example.com")
                                .role("Manager")
                                .build()
                ))
                .createdAt(LocalDateTime.now())
                .build();
        app = applicationRepository.save(app);

        // Update with new stakeholders
        String requestBody = String.format("""
                {
                    "name": "App With Stakeholders",
                    "ownerName": "Owner",
                    "ownerEmail": "owner@example.com",
                    "organizationId": "%s",
                    "applicationType": "Web Application",
                    "assessmentFrequency": "Quarterly",
                    "stakeHolders": [
                        {
                            "name": "New Stakeholder 1",
                            "email": "stakeholder1@example.com",
                            "role": "Product Owner"
                        },
                        {
                            "name": "New Stakeholder 2",
                            "email": "stakeholder2@example.com",
                            "role": "Tech Lead"
                        }
                    ]
                }
                """, testOrganization1.getId());

        mockMvc.perform(put("/api/v1/applications/" + app.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Application updated successfully"))
                .andExpect(jsonPath("$.data.stakeHolders").isArray())
                .andExpect(jsonPath("$.data.stakeHolders.length()").value(2))
                .andExpect(jsonPath("$.data.stakeHolders[0].name").value("New Stakeholder 1"))
                .andExpect(jsonPath("$.data.stakeHolders[0].role").value("Product Owner"))
                .andExpect(jsonPath("$.data.stakeHolders[1].name").value("New Stakeholder 2"))
                .andExpect(jsonPath("$.data.stakeHolders[1].role").value("Tech Lead"));
    }

    @Test
    void updateApplication_ChangeAssessmentFrequency_UpdatesSuccessfully() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create application with QUARTERLY frequency
        Application app = Application.builder()
                .name("App To Update Frequency")
                .ownerName("Owner")
                .ownerEmail("owner@example.com")
                .organizationId(testOrganization1.getId())
                .applicationType("API")
                .assessmentFrequency("Quarterly")
                .createdAt(LocalDateTime.now())
                .build();
        app = applicationRepository.save(app);

        // Update to YEARLY frequency
        String requestBody = String.format("""
                {
                    "name": "App To Update Frequency",
                    "ownerName": "Owner",
                    "ownerEmail": "owner@example.com",
                    "organizationId": "%s",
                    "applicationType": "API",
                    "assessmentFrequency": "Yearly"
                }
                """, testOrganization1.getId());

        mockMvc.perform(put("/api/v1/applications/" + app.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assessmentFrequency").value("Yearly"));

        // Verify in database
        Application updatedApp = applicationRepository.findById(app.getId()).orElseThrow();
        assertThat(updatedApp.getAssessmentFrequency()).isEqualTo("Yearly");
    }

    // ==================== MOVE APPLICATION TESTS ====================

    @Test
    void moveApplication_FromOneOrgToAnother_MovesSuccessfully() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create application in organization 1
        Application app = Application.builder()
                .name("App To Move")
                .ownerName("Owner")
                .ownerEmail("owner@example.com")
                .organizationId(testOrganization1.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .createdAt(LocalDateTime.now())
                .build();
        app = applicationRepository.save(app);

        // Move to organization 2
        mockMvc.perform(put("/api/v1/applications/" + app.getId() + "/move/" + testOrganization2.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Application moved successfully"))
                .andExpect(jsonPath("$.data.organizationId").value(testOrganization2.getId()));

        // Verify app is no longer in org 1
        mockMvc.perform(get("/api/v1/applications/organization/" + testOrganization1.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // Verify app is now in org 2
        mockMvc.perform(get("/api/v1/applications/organization/" + testOrganization2.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("App To Move"));
    }

    @Test
    void moveApplication_ToNonExistentOrg_ReturnsNotFound() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        Application app = Application.builder()
                .name("App To Move")
                .ownerName("Owner")
                .ownerEmail("owner@example.com")
                .organizationId(testOrganization1.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .createdAt(LocalDateTime.now())
                .build();
        app = applicationRepository.save(app);

        mockMvc.perform(put("/api/v1/applications/" + app.getId() + "/move/nonexistent-org")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Organization not found with id: nonexistent-org"));
    }

    // ==================== DELETE APPLICATION TESTS ====================

    @Test
    void deleteApplication_AsSuperAdmin_DeletesSuccessfully() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        Application app = Application.builder()
                .name("App To Delete")
                .ownerName("Owner")
                .ownerEmail("owner@example.com")
                .organizationId(testOrganization1.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .createdAt(LocalDateTime.now())
                .build();
        app = applicationRepository.save(app);

        mockMvc.perform(delete("/api/v1/applications/" + app.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Application deleted successfully"));

        // Verify application was deleted
        assertThat(applicationRepository.findById(app.getId())).isEmpty();
    }

    @Test
    void deleteApplication_WithNonExistentId_ReturnsNotFound() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        mockMvc.perform(delete("/api/v1/applications/nonexistent-id")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Application not found with id: nonexistent-id"));
    }

    // ==================== GET APPLICATION TESTS ====================

    @Test
    void getAllApplications_AsSuperAdmin_ReturnsAllApplications() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create multiple applications
        Application app1 = Application.builder()
                .name("App 1")
                .ownerName("Owner 1")
                .ownerEmail("owner1@example.com")
                .organizationId(testOrganization1.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .createdAt(LocalDateTime.now())
                .build();
        applicationRepository.save(app1);

        Application app2 = Application.builder()
                .name("App 2")
                .ownerName("Owner 2")
                .ownerEmail("owner2@example.com")
                .organizationId(testOrganization2.getId())
                .applicationType("Mobile Application")
                .assessmentFrequency("Yearly")
                .createdAt(LocalDateTime.now())
                .build();
        applicationRepository.save(app2);

        mockMvc.perform(get("/api/v1/applications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.pagination.totalElements").value(2));
    }

    // ── CSV sync (admin only) ──────────────────────────────────────────────────

    private MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "apps.csv", "text/csv",
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void importApplications_AsSuperAdmin_UpsertsAndReportsRows() throws Exception {
        applicationRepository.save(Application.builder()
                .appId("APP-1").name("Existing").createdAt(LocalDateTime.now()).build());
        String token = jwtService.generateToken(superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin")));

        mockMvc.perform(multipart("/api/v1/applications/import")
                        .file(csv("""
                                appId,name,organization,subOrganization,status
                                APP-1,Renamed,Acme,Payments,PRODUCTION
                                APP-2,Brand New,Acme,Payments,TESTING
                                APP-3,Bad Row,Acme,Payments,RETIRED
                                """))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(3))
                .andExpect(jsonPath("$.data.created").value(1))
                .andExpect(jsonPath("$.data.updated").value(1))
                .andExpect(jsonPath("$.data.failed").value(1))
                .andExpect(jsonPath("$.data.createdOrganizations[0]").value("Acme"))
                .andExpect(jsonPath("$.data.createdSubOrganizations[0]").value("Acme / Payments"))
                .andExpect(jsonPath("$.data.errors[0].line").value(4))
                .andExpect(jsonPath("$.data.errors[0].identifier").value("Bad Row"));

        assertThat(applicationRepository.findByAppId("APP-1").orElseThrow().getName())
                .isEqualTo("Renamed");
    }

    @Test
    void importApplications_WithoutSuperAdmin_IsForbidden() throws Exception {
        // Managing every organization's applications at once is an administrator's job — the
        // ordinary application permissions are not enough.
        Role editorRole = roleRepository.save(Role.builder().name("AppEditor")
                .description("Full application permissions")
                .permissions(List.of("applications:create:all", "applications:edit:all",
                        "applications:read:all")).build());
        User editor = userRepository.save(User.builder()
                .username("appeditor").email("appeditor@test.com")
                .firstName("App").lastName("Editor")
                .password(passwordEncoder.encode("password")).loginOption(LoginOption.NATIVE)
                .roleIds(List.of(editorRole.getId())).teamIds(new ArrayList<>())
                .isInternal(true).createdAt(LocalDateTime.now()).failedLoginAttempts(0).build());
        String token = jwtService.generateToken(editor.getUsername(),
                List.of(new SimpleGrantedAuthority("applications:create:all"),
                        new SimpleGrantedAuthority("applications:edit:all"),
                        new SimpleGrantedAuthority("applications:read:all")));

        mockMvc.perform(multipart("/api/v1/applications/import")
                        .file(csv("name\nNope\n"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/applications/import/template")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        assertThat(applicationRepository.findByName("Nope")).isEmpty();
    }

    @Test
    void importTemplate_IsDownloadableByAdmins() throws Exception {
        String token = jwtService.generateToken(superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin")));

        mockMvc.perform(get("/api/v1/applications/import/template")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=application-import-template.csv"))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith(
                        "appId,name,description,organization,subOrganization,status")));
    }

    @Test
    void importApplications_RejectsAnUnknownColumn() throws Exception {
        String token = jwtService.generateToken(superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin")));

        mockMvc.perform(multipart("/api/v1/applications/import")
                        .file(csv("name,widgets\nCheckout,3\n"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    // ── List filters ───────────────────────────────────────────────────────────

    /** Two organizations, two divisions in the first, and four applications across them. */
    private String seedFilterFixtures() {
        SubOrganization payments = subOrganizationRepository.save(SubOrganization.builder()
                .organizationId(testOrganization1.getId()).name("Payments").build());
        SubOrganization platform = subOrganizationRepository.save(SubOrganization.builder()
                .organizationId(testOrganization1.getId()).name("Platform").build());

        applicationRepository.save(Application.builder().name("Checkout")
                .organizationId(testOrganization1.getId()).subOrganizationId(payments.getId())
                .status(ApplicationStatus.PRODUCTION).createdAt(LocalDateTime.now()).build());
        applicationRepository.save(Application.builder().name("Refunds")
                .organizationId(testOrganization1.getId()).subOrganizationId(payments.getId())
                .status(ApplicationStatus.DECOMMISSIONED).createdAt(LocalDateTime.now()).build());
        applicationRepository.save(Application.builder().name("Pipelines")
                .organizationId(testOrganization1.getId()).subOrganizationId(platform.getId())
                .status(ApplicationStatus.PRODUCTION).createdAt(LocalDateTime.now()).build());
        applicationRepository.save(Application.builder().name("Ledger")
                .organizationId(testOrganization2.getId())
                .status(ApplicationStatus.PRODUCTION).createdAt(LocalDateTime.now()).build());
        return payments.getId();
    }

    @Test
    void getAllApplications_FilteredBySubOrganization() throws Exception {
        String paymentsId = seedFilterFixtures();
        String token = jwtService.generateToken(superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin")));

        mockMvc.perform(get("/api/v1/applications")
                        .param("subOrganizationId", paymentsId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].name", containsInAnyOrder("Checkout", "Refunds")))
                .andExpect(jsonPath("$.pagination.totalElements").value(2));
    }

    @Test
    void getAllApplications_FilteredByStatus() throws Exception {
        seedFilterFixtures();
        String token = jwtService.generateToken(superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin")));

        mockMvc.perform(get("/api/v1/applications")
                        .param("status", "decommissioned")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Refunds"));
    }

    @Test
    void getAllApplications_FiltersCombineWithEachOtherAndWithSearch() throws Exception {
        String paymentsId = seedFilterFixtures();
        String token = jwtService.generateToken(superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin")));

        mockMvc.perform(get("/api/v1/applications")
                        .param("organizationId", testOrganization1.getId())
                        .param("subOrganizationId", paymentsId)
                        .param("status", "PRODUCTION")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Checkout"));

        // …and alongside the search box, which still narrows within the filters.
        mockMvc.perform(get("/api/v1/applications")
                        .param("organizationId", testOrganization1.getId())
                        .param("search", "ref")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Refunds"));
    }

    @Test
    void getAllApplications_UnknownStatusIsRejected() throws Exception {
        String token = jwtService.generateToken(superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin")));

        mockMvc.perform(get("/api/v1/applications")
                        .param("status", "retired")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getAllApplications_FiltersApplyToOrgScopedUsersToo() throws Exception {
        String paymentsId = seedFilterFixtures();
        // An org-scoped user sees only their organization, and the filters narrow within it.
        Role orgRole = roleRepository.save(Role.builder().name("OrgReader")
                .description("Org-scoped reader").permissions(List.of("applications:read:org")).build());
        User orgUser = userRepository.save(User.builder()
                .username("orguser").email("orguser@test.com").firstName("Org").lastName("User")
                .password(passwordEncoder.encode("password")).loginOption(LoginOption.NATIVE)
                .roleIds(List.of(orgRole.getId())).teamIds(new ArrayList<>())
                .isInternal(false).organizationId(testOrganization1.getId())
                .createdAt(LocalDateTime.now()).failedLoginAttempts(0).build());
        String token = jwtService.generateToken(orgUser.getUsername(),
                List.of(new SimpleGrantedAuthority("applications:read:org")));

        mockMvc.perform(get("/api/v1/applications")
                        .param("subOrganizationId", paymentsId)
                        .param("status", "PRODUCTION")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Checkout"));
    }

    @Test
    void getApplicationById_AsSuperAdmin_ReturnsApplication() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        Application app = Application.builder()
                .name("Test App")
                .ownerName("Owner")
                .ownerEmail("owner@example.com")
                .organizationId(testOrganization1.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .createdAt(LocalDateTime.now())
                .build();
        app = applicationRepository.save(app);

        mockMvc.perform(get("/api/v1/applications/" + app.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(app.getId()))
                .andExpect(jsonPath("$.data.name").value("Test App"));
    }

    // ==================== FIELD VALUES TESTS ====================

    @Test
    void createApplication_WithFieldValues_StoresAndReturnsFieldValues() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create a field config for APPLICATION scope
        String fieldId = UUID.randomUUID().toString();
        EntityFieldConfig config = EntityFieldConfig.builder()
                .scope(FieldScope.APPLICATION)
                .fieldDefinitions(List.of(
                        UserDefinedField.builder()
                                .id(fieldId)
                                .variableName("risk_tier")
                                .displayName("Risk Tier")
                                .fieldType(FieldType.STRING)
                                .fieldScope(FieldScope.APPLICATION)
                                .displayOrder(1)
                                .build()
                ))
                .build();
        entityFieldConfigRepository.save(config);

        String requestBody = String.format("""
                {
                    "name": "App With Fields",
                    "organizationId": "%s",
                    "applicationType": "Web",
                    "assessmentFrequency": "Quarterly",
                    "fieldValues": {
                        "%s": "High"
                    }
                }
                """, testOrganization1.getId(), fieldId);

        mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fieldValues." + fieldId).value("High"))
                .andExpect(jsonPath("$.data.fieldDefinitions").isArray())
                .andExpect(jsonPath("$.data.fieldDefinitions.length()").value(1))
                .andExpect(jsonPath("$.data.fieldDefinitions[0].variableName").value("risk_tier"));
    }

    @Test
    void updateApplication_WithFieldValues_UpdatesFieldValues() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        String fieldId = UUID.randomUUID().toString();
        EntityFieldConfig config = EntityFieldConfig.builder()
                .scope(FieldScope.APPLICATION)
                .fieldDefinitions(List.of(
                        UserDefinedField.builder()
                                .id(fieldId)
                                .variableName("data_classification")
                                .displayName("Data Classification")
                                .fieldType(FieldType.DROPDOWN)
                                .fieldScope(FieldScope.APPLICATION)
                                .displayOrder(1)
                                .build()
                ))
                .build();
        entityFieldConfigRepository.save(config);

        Application app = Application.builder()
                .name("App To Update Fields")
                .organizationId(testOrganization1.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .createdAt(LocalDateTime.now())
                .build();
        app = applicationRepository.save(app);

        String requestBody = String.format("""
                {
                    "name": "App To Update Fields",
                    "organizationId": "%s",
                    "applicationType": "Web Application",
                    "assessmentFrequency": "Quarterly",
                    "fieldValues": {
                        "%s": "Confidential"
                    }
                }
                """, testOrganization1.getId(), fieldId);

        mockMvc.perform(put("/api/v1/applications/" + app.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fieldValues." + fieldId).value("Confidential"));
    }

    @Test
    void getApplicationById_ReturnsFieldDefinitionsAndValues() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        String fieldId = UUID.randomUUID().toString();
        EntityFieldConfig config = EntityFieldConfig.builder()
                .scope(FieldScope.APPLICATION)
                .fieldDefinitions(List.of(
                        UserDefinedField.builder()
                                .id(fieldId)
                                .variableName("sla_tier")
                                .displayName("SLA Tier")
                                .fieldType(FieldType.STRING)
                                .fieldScope(FieldScope.APPLICATION)
                                .displayOrder(1)
                                .build()
                ))
                .build();
        entityFieldConfigRepository.save(config);

        Application app = Application.builder()
                .name("App With Existing Fields")
                .organizationId(testOrganization1.getId())
                .applicationType("API")
                .assessmentFrequency("Quarterly")
                .fieldValues(new java.util.HashMap<>(java.util.Map.of(fieldId, "Gold")))
                .createdAt(LocalDateTime.now())
                .build();
        app = applicationRepository.save(app);

        mockMvc.perform(get("/api/v1/applications/" + app.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fieldDefinitions.length()").value(1))
                .andExpect(jsonPath("$.data.fieldDefinitions[0].variableName").value("sla_tier"))
                .andExpect(jsonPath("$.data.fieldValues." + fieldId).value("Gold"));
    }

    // ==================== ASSIGNED USER TESTS ====================

    @Test
    void assignUser_AsSuperAdmin_ReturnsCreatedAssignment() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        User targetUser = User.builder()
                .username("targetuser")
                .email("target@example.com")
                .firstName("Target")
                .lastName("User")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        targetUser = userRepository.save(targetUser);

        Application app = Application.builder()
                .name("App For Assignment")
                .organizationId(testOrganization1.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .createdAt(LocalDateTime.now())
                .build();
        app = applicationRepository.save(app);

        String requestBody = String.format("""
                {"userId": "%s", "accessLevel": "WRITE"}
                """, targetUser.getId());

        mockMvc.perform(post("/api/v1/applications/" + app.getId() + "/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(targetUser.getId()))
                .andExpect(jsonPath("$.data.email").value("target@example.com"))
                .andExpect(jsonPath("$.data.accessLevel").value("WRITE"));
    }

    @Test
    void assignUser_AsReadOnlyUser_ReturnsForbidden() throws Exception {
        String token = jwtService.generateToken(
                "readonlyuser",
                List.of(new SimpleGrantedAuthority("applications:read:all"))
        );

        Application app = Application.builder()
                .name("App For Assign Forbidden Test")
                .organizationId(testOrganization1.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .createdAt(LocalDateTime.now())
                .build();
        app = applicationRepository.save(app);

        mockMvc.perform(post("/api/v1/applications/" + app.getId() + "/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"userId\": \"someid\", \"accessLevel\": \"WRITE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAllApplications_AsOwnedUser_ReturnsOnlyAssignedApps() throws Exception {
        User ownedUser = User.builder()
                .username("owneduser")
                .email("owned@example.com")
                .firstName("Owned")
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
                .displayName("Owned User")
                .email("owned@example.com")
                .accessLevel("WRITE")
                .build();

        Application assignedApp = Application.builder()
                .name("Assigned App")
                .organizationId(testOrganization1.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .assignedUsers(new ArrayList<>(List.of(assignedUser)))
                .createdAt(LocalDateTime.now())
                .build();
        applicationRepository.save(assignedApp);

        Application unassignedApp = Application.builder()
                .name("Unassigned App")
                .organizationId(testOrganization1.getId())
                .applicationType("API")
                .assessmentFrequency("Yearly")
                .createdAt(LocalDateTime.now())
                .build();
        applicationRepository.save(unassignedApp);

        String token = jwtService.generateToken(
                ownedUser.getUsername(),
                List.of(new SimpleGrantedAuthority("applications:read:owned"))
        );

        mockMvc.perform(get("/api/v1/applications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Assigned App"));
    }

    @Test
    void getApplicationById_AsOwnedUser_NotAssigned_ReturnsNotFound() throws Exception {
        User ownedUser = User.builder()
                .username("owneduser2")
                .email("owned2@example.com")
                .firstName("Owned2")
                .lastName("User2")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        ownedUser = userRepository.save(ownedUser);

        Application app = Application.builder()
                .name("Not Assigned App")
                .organizationId(testOrganization1.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .createdAt(LocalDateTime.now())
                .build();
        app = applicationRepository.save(app);

        String token = jwtService.generateToken(
                ownedUser.getUsername(),
                List.of(new SimpleGrantedAuthority("applications:read:owned"))
        );

        mockMvc.perform(get("/api/v1/applications/" + app.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateApplication_AsOwnedUserWithWriteAccess_ReturnsOk() throws Exception {
        User ownedUser = User.builder()
                .username("ownedwriter")
                .email("ownedwriter@example.com")
                .firstName("Writer")
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
                .displayName("Writer User")
                .email("ownedwriter@example.com")
                .accessLevel("WRITE")
                .build();

        Application app = Application.builder()
                .name("Writable App")
                .organizationId(testOrganization1.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .assignedUsers(new ArrayList<>(List.of(assignedUser)))
                .createdAt(LocalDateTime.now())
                .build();
        app = applicationRepository.save(app);

        String token = jwtService.generateToken(
                ownedUser.getUsername(),
                List.of(new SimpleGrantedAuthority("applications:read:owned"))
        );

        String requestBody = String.format("""
                {
                    "name": "Writable App",
                    "organizationId": "%s",
                    "applicationType": "Web Application",
                    "assessmentFrequency": "Yearly"
                }
                """, testOrganization1.getId());

        mockMvc.perform(put("/api/v1/applications/" + app.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk());
    }

    @Test
    void updateApplication_AsOwnedUserWithReadAccess_ReturnsForbidden() throws Exception {
        User ownedUser = User.builder()
                .username("ownedreader")
                .email("ownedreader@example.com")
                .firstName("Reader")
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
                .displayName("Reader User")
                .email("ownedreader@example.com")
                .accessLevel("READ")
                .build();

        Application app = Application.builder()
                .name("Read Only App")
                .organizationId(testOrganization1.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .assignedUsers(new ArrayList<>(List.of(assignedUser)))
                .createdAt(LocalDateTime.now())
                .build();
        app = applicationRepository.save(app);

        String token = jwtService.generateToken(
                ownedUser.getUsername(),
                List.of(new SimpleGrantedAuthority("applications:read:owned"))
        );

        String requestBody = String.format("""
                {
                    "name": "Read Only App",
                    "organizationId": "%s",
                    "applicationType": "Web Application",
                    "assessmentFrequency": "Yearly"
                }
                """, testOrganization1.getId());

        mockMvc.perform(put("/api/v1/applications/" + app.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isForbidden());
    }

    @Test
    void removeAssignedUser_AsSuperAdmin_ReturnsNoContent() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        User targetUser = User.builder()
                .username("removeuser")
                .email("remove@example.com")
                .firstName("Remove")
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
                .displayName("Remove User")
                .email("remove@example.com")
                .accessLevel("WRITE")
                .build();

        Application app = Application.builder()
                .name("App With User To Remove")
                .organizationId(testOrganization1.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .assignedUsers(new ArrayList<>(List.of(assignedUser)))
                .createdAt(LocalDateTime.now())
                .build();
        app = applicationRepository.save(app);

        mockMvc.perform(delete("/api/v1/applications/" + app.getId() + "/users/" + targetUser.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Application updated = applicationRepository.findById(app.getId()).orElseThrow();
        assertThat(updated.getAssignedUsers()).isEmpty();
    }

    @Test
    void getApplicationsByOrganization_ReturnsOnlyAppsForThatOrg() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create apps in org 1
        Application app1 = Application.builder()
                .name("Org 1 App 1")
                .ownerName("Owner")
                .ownerEmail("owner@example.com")
                .organizationId(testOrganization1.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .createdAt(LocalDateTime.now())
                .build();
        applicationRepository.save(app1);

        Application app2 = Application.builder()
                .name("Org 1 App 2")
                .ownerName("Owner")
                .ownerEmail("owner@example.com")
                .organizationId(testOrganization1.getId())
                .applicationType("Mobile Application")
                .assessmentFrequency("Yearly")
                .createdAt(LocalDateTime.now())
                .build();
        applicationRepository.save(app2);

        // Create app in org 2
        Application app3 = Application.builder()
                .name("Org 2 App 1")
                .ownerName("Owner")
                .ownerEmail("owner@example.com")
                .organizationId(testOrganization2.getId())
                .applicationType("API")
                .assessmentFrequency("Ad Hoc")
                .createdAt(LocalDateTime.now())
                .build();
        applicationRepository.save(app3);

        // Get apps for org 1
        mockMvc.perform(get("/api/v1/applications/organization/" + testOrganization1.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2));

        // Get apps for org 2
        mockMvc.perform(get("/api/v1/applications/organization/" + testOrganization2.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // ==================== OWNED ACCESS SCENARIOS ====================

    @Test
    void ownedUser_WithWriteAccess_CanListAndViewAndUpdateAssignedApp() throws Exception {
        User ownedUser = User.builder()
                .username("writeowner")
                .email("writeowner@example.com")
                .firstName("Write")
                .lastName("Owner")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        ownedUser = userRepository.save(ownedUser);

        AssignedUser assignment = AssignedUser.builder()
                .userId(ownedUser.getId())
                .displayName("Write Owner")
                .email("writeowner@example.com")
                .accessLevel("WRITE")
                .build();

        Application app = Application.builder()
                .name("Write Access App")
                .organizationId(testOrganization1.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .assignedUsers(new ArrayList<>(List.of(assignment)))
                .createdAt(LocalDateTime.now())
                .build();
        app = applicationRepository.save(app);

        String token = jwtService.generateToken(
                ownedUser.getUsername(),
                List.of(new SimpleGrantedAuthority("applications:read:owned"))
        );

        // Can list applications — sees only the assigned app
        mockMvc.perform(get("/api/v1/applications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Write Access App"));

        // Can view the assigned app by ID
        mockMvc.perform(get("/api/v1/applications/" + app.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Write Access App"));

        // Can update the assigned app
        String updateBody = String.format("""
                {
                    "name": "Write Access App Updated",
                    "organizationId": "%s",
                    "applicationType": "API",
                    "assessmentFrequency": "Yearly"
                }
                """, testOrganization1.getId());

        mockMvc.perform(put("/api/v1/applications/" + app.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Write Access App Updated"));
    }

    @Test
    void ownedUser_WithReadAccess_CanListAndViewButNotUpdateAssignedApp() throws Exception {
        User ownedUser = User.builder()
                .username("readonlyowner")
                .email("readonlyowner@example.com")
                .firstName("ReadOnly")
                .lastName("Owner")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        ownedUser = userRepository.save(ownedUser);

        AssignedUser assignment = AssignedUser.builder()
                .userId(ownedUser.getId())
                .displayName("ReadOnly Owner")
                .email("readonlyowner@example.com")
                .accessLevel("READ")
                .build();

        Application app = Application.builder()
                .name("Read Only Access App")
                .organizationId(testOrganization1.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .assignedUsers(new ArrayList<>(List.of(assignment)))
                .createdAt(LocalDateTime.now())
                .build();
        app = applicationRepository.save(app);

        String token = jwtService.generateToken(
                ownedUser.getUsername(),
                List.of(new SimpleGrantedAuthority("applications:read:owned"))
        );

        // Can list applications
        mockMvc.perform(get("/api/v1/applications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Read Only Access App"));

        // Can view the assigned app by ID
        mockMvc.perform(get("/api/v1/applications/" + app.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Read Only Access App"));

        // Cannot update — READ access level does not allow edits
        String updateBody = String.format("""
                {
                    "name": "Read Only Access App",
                    "organizationId": "%s",
                    "applicationType": "API",
                    "assessmentFrequency": "Yearly"
                }
                """, testOrganization1.getId());

        mockMvc.perform(put("/api/v1/applications/" + app.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(updateBody))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownedUser_WithNoAssignedApplications_SeesEmptyList() throws Exception {
        User ownedUser = User.builder()
                .username("unassignedowner")
                .email("unassignedowner@example.com")
                .firstName("Unassigned")
                .lastName("Owner")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        ownedUser = userRepository.save(ownedUser);

        // Create an app not assigned to this user
        applicationRepository.save(Application.builder()
                .name("Someone Elses App")
                .organizationId(testOrganization1.getId())
                .applicationType("Web Application")
                .assessmentFrequency("Quarterly")
                .createdAt(LocalDateTime.now())
                .build());

        String token = jwtService.generateToken(
                ownedUser.getUsername(),
                List.of(new SimpleGrantedAuthority("applications:read:owned"))
        );

        mockMvc.perform(get("/api/v1/applications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ==================== SUPER ADMIN FULL ACCESS ====================

    @Test
    void superAdmin_CanCreateReadUpdateDeleteApplications() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create
        String createBody = String.format("""
                {
                    "name": "SuperAdmin Test App",
                    "description": "Full access test",
                    "ownerName": "Admin",
                    "ownerEmail": "admin@example.com",
                    "organizationId": "%s",
                    "applicationType": "Web Application",
                    "assessmentFrequency": "Yearly"
                }
                """, testOrganization1.getId());

        String createResponse = mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("SuperAdmin Test App"))
                .andReturn().getResponse().getContentAsString();

        String appId = objectMapper.readTree(createResponse).path("data").path("id").asText();

        // Read list
        mockMvc.perform(get("/api/v1/applications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.name == 'SuperAdmin Test App')]").exists());

        // Read by ID
        mockMvc.perform(get("/api/v1/applications/" + appId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(appId));

        // Update
        String updateBody = String.format("""
                {
                    "name": "SuperAdmin Test App Updated",
                    "organizationId": "%s",
                    "applicationType": "API",
                    "assessmentFrequency": "Quarterly"
                }
                """, testOrganization1.getId());

        mockMvc.perform(put("/api/v1/applications/" + appId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("SuperAdmin Test App Updated"));

        // Delete
        mockMvc.perform(delete("/api/v1/applications/" + appId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Verify deleted
        mockMvc.perform(get("/api/v1/applications/" + appId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
