package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.*;
import com.faction.clientportal.repository.*;
import com.faction.clientportal.service.JwtService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EntityFieldConfigControllerTest extends TestContainersConfig {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private EntityFieldConfigRepository entityFieldConfigRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private User superAdminUser;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        roleRepository.deleteAll();
        entityFieldConfigRepository.deleteAll();
        applicationRepository.deleteAll();
        organizationRepository.deleteAll();

        Role superAdminRole = Role.builder()
                .name("SuperAdmin")
                .description("Super Administrator with full access")
                .permissions(List.of("super_admin"))
                .build();
        superAdminRole = roleRepository.save(superAdminRole);

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

    // ==================== GET FIELD CONFIG TESTS ====================

    @Test
    void getFieldConfig_ForApplication_ReturnsEmptyConfigWhenNoneExists() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        mockMvc.perform(get("/api/v1/entity-fields/APPLICATION")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.scope").value("APPLICATION"))
                .andExpect(jsonPath("$.data.fieldDefinitions").isArray())
                .andExpect(jsonPath("$.data.fieldDefinitions.length()").value(0));
    }

    @Test
    void getFieldConfig_ForOrganization_ReturnsEmptyConfigWhenNoneExists() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        mockMvc.perform(get("/api/v1/entity-fields/ORGANIZATION")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.scope").value("ORGANIZATION"))
                .andExpect(jsonPath("$.data.fieldDefinitions").isArray())
                .andExpect(jsonPath("$.data.fieldDefinitions.length()").value(0));
    }

    // ==================== UPDATE FIELD CONFIG TESTS ====================

    @Test
    void updateFieldConfig_ForApplication_CreatesFieldDefinitions() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        String requestBody = """
                {
                    "fieldDefinitions": [
                        {
                            "variableName": "risk_tier",
                            "displayName": "Risk Tier",
                            "fieldType": "DROPDOWN",
                            "dropdownOptions": ["Critical", "High", "Medium", "Low"],
                            "required": true,
                            "displayOrder": 1,
                            "fieldScope": "APPLICATION"
                        },
                        {
                            "variableName": "business_owner",
                            "displayName": "Business Owner",
                            "fieldType": "STRING",
                            "required": false,
                            "displayOrder": 2,
                            "fieldScope": "APPLICATION"
                        }
                    ]
                }
                """;

        mockMvc.perform(put("/api/v1/entity-fields/APPLICATION")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.scope").value("APPLICATION"))
                .andExpect(jsonPath("$.data.fieldDefinitions").isArray())
                .andExpect(jsonPath("$.data.fieldDefinitions.length()").value(2))
                .andExpect(jsonPath("$.data.fieldDefinitions[0].variableName").value("risk_tier"))
                .andExpect(jsonPath("$.data.fieldDefinitions[0].fieldType").value("DROPDOWN"))
                .andExpect(jsonPath("$.data.fieldDefinitions[0].dropdownOptions.length()").value(4))
                .andExpect(jsonPath("$.data.fieldDefinitions[1].variableName").value("business_owner"))
                .andExpect(jsonPath("$.data.fieldDefinitions[1].id").exists());
    }

    @Test
    void updateFieldConfig_ForOrganization_CreatesFieldDefinitions() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        String requestBody = """
                {
                    "fieldDefinitions": [
                        {
                            "variableName": "industry",
                            "displayName": "Industry",
                            "fieldType": "DROPDOWN",
                            "dropdownOptions": ["Finance", "Healthcare", "Technology", "Retail"],
                            "required": false,
                            "displayOrder": 1,
                            "fieldScope": "ORGANIZATION"
                        }
                    ]
                }
                """;

        mockMvc.perform(put("/api/v1/entity-fields/ORGANIZATION")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope").value("ORGANIZATION"))
                .andExpect(jsonPath("$.data.fieldDefinitions.length()").value(1))
                .andExpect(jsonPath("$.data.fieldDefinitions[0].variableName").value("industry"));
    }

    @Test
    void updateFieldConfig_ThenGet_ReturnsPersisted() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        String requestBody = """
                {
                    "fieldDefinitions": [
                        {
                            "variableName": "contract_value",
                            "displayName": "Contract Value",
                            "fieldType": "STRING",
                            "displayOrder": 1,
                            "fieldScope": "APPLICATION"
                        }
                    ]
                }
                """;

        mockMvc.perform(put("/api/v1/entity-fields/APPLICATION")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/entity-fields/APPLICATION")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fieldDefinitions.length()").value(1))
                .andExpect(jsonPath("$.data.fieldDefinitions[0].variableName").value("contract_value"));
    }

    @Test
    void updateFieldConfig_UpdateExisting_ReplacesFieldDefinitions() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create initial config
        String initialBody = """
                {
                    "fieldDefinitions": [
                        {
                            "variableName": "old_field",
                            "displayName": "Old Field",
                            "fieldType": "STRING",
                            "displayOrder": 1,
                            "fieldScope": "APPLICATION"
                        }
                    ]
                }
                """;
        mockMvc.perform(put("/api/v1/entity-fields/APPLICATION")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(initialBody))
                .andExpect(status().isOk());

        // Replace with new config
        String updatedBody = """
                {
                    "fieldDefinitions": [
                        {
                            "variableName": "new_field",
                            "displayName": "New Field",
                            "fieldType": "DROPDOWN",
                            "dropdownOptions": ["A", "B"],
                            "displayOrder": 1,
                            "fieldScope": "APPLICATION"
                        }
                    ]
                }
                """;
        mockMvc.perform(put("/api/v1/entity-fields/APPLICATION")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(updatedBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fieldDefinitions.length()").value(1))
                .andExpect(jsonPath("$.data.fieldDefinitions[0].variableName").value("new_field"));
    }

    @Test
    void updateFieldConfig_WithEmptyList_ClearsFieldDefinitions() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create initial config
        String initialBody = """
                {
                    "fieldDefinitions": [
                        {
                            "variableName": "some_field",
                            "displayName": "Some Field",
                            "fieldType": "STRING",
                            "displayOrder": 1,
                            "fieldScope": "APPLICATION"
                        }
                    ]
                }
                """;
        mockMvc.perform(put("/api/v1/entity-fields/APPLICATION")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(initialBody))
                .andExpect(status().isOk());

        // Clear fields
        String clearBody = """
                {
                    "fieldDefinitions": []
                }
                """;
        mockMvc.perform(put("/api/v1/entity-fields/APPLICATION")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(clearBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fieldDefinitions.length()").value(0));
    }

    @Test
    void updateFieldConfig_WithoutSuperAdmin_ReturnsForbidden() throws Exception {
        Role readOnlyRole = Role.builder()
                .name("ReadOnly")
                .permissions(List.of("applications:read:all"))
                .build();
        readOnlyRole = roleRepository.save(readOnlyRole);

        User readOnlyUser = User.builder()
                .username("readonly")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(readOnlyRole.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        readOnlyUser = userRepository.save(readOnlyUser);

        String token = jwtService.generateToken(
                readOnlyUser.getUsername(),
                List.of(new SimpleGrantedAuthority("applications:read:all"))
        );

        String requestBody = """
                {
                    "fieldDefinitions": []
                }
                """;

        mockMvc.perform(put("/api/v1/entity-fields/APPLICATION")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isForbidden());
    }

    // ==================== SYNC TESTS ====================

    @Test
    void updateFieldConfig_RemoveField_SyncsOrphanedValuesFromApplications() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // First create a field definition and capture its ID
        String createBody = """
                {
                    "fieldDefinitions": [
                        {
                            "variableName": "keep_field",
                            "displayName": "Keep Field",
                            "fieldType": "STRING"
                        },
                        {
                            "variableName": "remove_field",
                            "displayName": "Remove Field",
                            "fieldType": "STRING"
                        }
                    ]
                }
                """;
        String createResponse = mockMvc.perform(put("/api/v1/entity-fields/APPLICATION")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Extract the IDs from the created config
        EntityFieldConfig config = entityFieldConfigRepository.findByScope(FieldScope.APPLICATION).orElseThrow();
        String keepFieldId = config.getFieldDefinitions().get(0).getId();
        String removeFieldId = config.getFieldDefinitions().get(1).getId();

        // Create an application with both field values
        Map<String, String> fieldValues = new HashMap<>();
        fieldValues.put(keepFieldId, "keep_value");
        fieldValues.put(removeFieldId, "should_be_removed");
        Application app = Application.builder()
                .name("Test App Sync")
                .fieldValues(fieldValues)
                .createdBy("test")
                .lastUpdatedBy("test")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        app = applicationRepository.save(app);

        // Update config — remove the second field (keep only keepFieldId)
        String updateBody = String.format("""
                {
                    "fieldDefinitions": [
                        {
                            "id": "%s",
                            "variableName": "keep_field",
                            "displayName": "Keep Field",
                            "fieldType": "STRING"
                        }
                    ]
                }
                """, keepFieldId);
        mockMvc.perform(put("/api/v1/entity-fields/APPLICATION")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fieldDefinitions.length()").value(1));

        // Verify the application no longer has the removed field value
        Application updatedApp = applicationRepository.findById(app.getId()).orElseThrow();
        assertThat(updatedApp.getFieldValues()).containsKey(keepFieldId);
        assertThat(updatedApp.getFieldValues()).doesNotContainKey(removeFieldId);
    }

    @Test
    void updateFieldConfig_RemoveField_SyncsOrphanedValuesFromOrganizations() throws Exception {
        String token = jwtService.generateToken(
                superAdminUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create field definitions
        String createBody = """
                {
                    "fieldDefinitions": [
                        {
                            "variableName": "industry",
                            "displayName": "Industry",
                            "fieldType": "STRING"
                        },
                        {
                            "variableName": "temp_field",
                            "displayName": "Temp Field",
                            "fieldType": "STRING"
                        }
                    ]
                }
                """;
        mockMvc.perform(put("/api/v1/entity-fields/ORGANIZATION")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(createBody))
                .andExpect(status().isOk());

        EntityFieldConfig config = entityFieldConfigRepository.findByScope(FieldScope.ORGANIZATION).orElseThrow();
        String industryFieldId = config.getFieldDefinitions().get(0).getId();
        String tempFieldId = config.getFieldDefinitions().get(1).getId();

        // Create an organization with both field values
        Map<String, String> fieldValues = new HashMap<>();
        fieldValues.put(industryFieldId, "Finance");
        fieldValues.put(tempFieldId, "should_be_removed");
        Organization org = Organization.builder()
                .name("Test Org Sync")
                .description("Test")
                .fieldValues(fieldValues)
                .build();
        org = organizationRepository.save(org);

        // Remove temp field from config
        String updateBody = String.format("""
                {
                    "fieldDefinitions": [
                        {
                            "id": "%s",
                            "variableName": "industry",
                            "displayName": "Industry",
                            "fieldType": "STRING"
                        }
                    ]
                }
                """, industryFieldId);
        mockMvc.perform(put("/api/v1/entity-fields/ORGANIZATION")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fieldDefinitions.length()").value(1));

        // Verify the organization no longer has the removed field value
        Organization updatedOrg = organizationRepository.findById(org.getId()).orElseThrow();
        assertThat(updatedOrg.getFieldValues()).containsKey(industryFieldId);
        assertThat(updatedOrg.getFieldValues()).doesNotContainKey(tempFieldId);
    }
}
