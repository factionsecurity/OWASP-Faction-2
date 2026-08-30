package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.AssessmentWorkflowConfigRepository;
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
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssessmentWorkflowConfigControllerTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private AssessmentWorkflowConfigRepository configRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String jwtToken;

    @BeforeEach
    void setUp() {
        configRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role superAdminRole = roleRepository.save(
                Role.builder().name("SuperAdmin").permissions(List.of("super_admin")).build());

        User testUser = userRepository.save(
                User.builder()
                        .username("wf-test-user")
                        .email("wf@test.com")
                        .password(passwordEncoder.encode("password"))
                        .firstName("WF")
                        .lastName("Tester")
                        .loginOption(LoginOption.NATIVE)
                        .roleIds(List.of(superAdminRole.getId()))
                        .isInternal(true)
                        .createdAt(LocalDateTime.now())
                        .build());

        jwtToken = jwtService.generateToken(
                testUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin")));
    }

    @Test
    void getConfig_returnsForbiddenWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/config/assessment-workflow"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getConfig_returnsDefaultsOnFirstAccess() throws Exception {
        mockMvc.perform(get("/api/v1/config/assessment-workflow")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newAssessmentStatus").value("New"))
                .andExpect(jsonPath("$.data.inProgressStatus").value("Testing"))
                .andExpect(jsonPath("$.data.completedStatus").value("Completed"))
                .andExpect(jsonPath("$.data.statuses", hasSize(8)));
    }

    @Test
    void getConfig_seedsDefaultVulnerabilitySlasOnFirstAccess() throws Exception {
        mockMvc.perform(get("/api/v1/config/assessment-workflow")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vulnerabilitySlas", hasSize(3)))
                .andExpect(jsonPath("$.data.vulnerabilitySlas[0].severity").value("CRITICAL"))
                .andExpect(jsonPath("$.data.vulnerabilitySlas[0].warningDays").value(20))
                .andExpect(jsonPath("$.data.vulnerabilitySlas[0].pastDueDays").value(30))
                .andExpect(jsonPath("$.data.vulnerabilitySlas[1].severity").value("HIGH"))
                .andExpect(jsonPath("$.data.vulnerabilitySlas[1].warningDays").value(30))
                .andExpect(jsonPath("$.data.vulnerabilitySlas[1].pastDueDays").value(60))
                .andExpect(jsonPath("$.data.vulnerabilitySlas[2].severity").value("MEDIUM"))
                .andExpect(jsonPath("$.data.vulnerabilitySlas[2].warningDays").value(300))
                .andExpect(jsonPath("$.data.vulnerabilitySlas[2].pastDueDays").value(365));
    }

    @Test
    void updateConfig_savesNewConfig() throws Exception {
        Map<String, Object> payload = Map.of(
                "statuses", List.of("Open", "In Progress", "Done"),
                "newAssessmentStatus", "Open",
                "inProgressStatus", "In Progress",
                "completedStatus", "Done"
        );

        mockMvc.perform(put("/api/v1/config/assessment-workflow")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newAssessmentStatus").value("Open"))
                .andExpect(jsonPath("$.data.completedStatus").value("Done"))
                .andExpect(jsonPath("$.data.statuses", hasSize(3)));

        // Verify GET returns updated config
        mockMvc.perform(get("/api/v1/config/assessment-workflow")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newAssessmentStatus").value("Open"));
    }

    @Test
    void updateConfig_savesVulnerabilitySlas() throws Exception {
        Map<String, Object> sla = Map.of(
                "severity", "CRITICAL",
                "pastDueDays", 30,
                "warningDays", 7
        );
        Map<String, Object> payload = Map.of(
                "statuses", List.of("New", "Done"),
                "newAssessmentStatus", "New",
                "inProgressStatus", "New",
                "completedStatus", "Done",
                "vulnerabilitySlas", List.of(sla)
        );

        mockMvc.perform(put("/api/v1/config/assessment-workflow")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vulnerabilitySlas", hasSize(1)))
                .andExpect(jsonPath("$.data.vulnerabilitySlas[0].severity").value("CRITICAL"))
                .andExpect(jsonPath("$.data.vulnerabilitySlas[0].pastDueDays").value(30))
                .andExpect(jsonPath("$.data.vulnerabilitySlas[0].warningDays").value(7));

        // Verify persisted
        mockMvc.perform(get("/api/v1/config/assessment-workflow")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vulnerabilitySlas", hasSize(1)))
                .andExpect(jsonPath("$.data.vulnerabilitySlas[0].severity").value("CRITICAL"));
    }

    @Test
    void updateConfig_savesCustomVulnerabilityStatuses() throws Exception {
        Map<String, Object> payload = Map.of(
                "statuses", List.of("New", "Done"),
                "newAssessmentStatus", "New",
                "inProgressStatus", "New",
                "completedStatus", "Done",
                "vulnerabilityStatuses", List.of("In Remediation", "Accepted Risk")
        );

        mockMvc.perform(put("/api/v1/config/assessment-workflow")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vulnerabilityStatuses", hasSize(2)))
                .andExpect(jsonPath("$.data.vulnerabilityStatuses", hasItems("In Remediation", "Accepted Risk")));

        // Verify persisted
        mockMvc.perform(get("/api/v1/config/assessment-workflow")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vulnerabilityStatuses", hasSize(2)));
    }

    @Test
    void getConfig_seedsDefaultRemediationStagesOnFirstAccess() throws Exception {
        mockMvc.perform(get("/api/v1/config/assessment-workflow")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remediationStages", hasSize(3)))
                .andExpect(jsonPath("$.data.remediationStages[0].id").value("development"))
                .andExpect(jsonPath("$.data.remediationStages[0].name").value("Development"))
                .andExpect(jsonPath("$.data.remediationStages[1].id").value("staging"))
                .andExpect(jsonPath("$.data.remediationStages[2].id").value("production"));
    }

    @Test
    void updateConfig_savesRemediationStages_generatingMissingIdsAndDroppingBlanks() throws Exception {
        Map<String, Object> payload = Map.of(
                "statuses", List.of("New", "Done"),
                "newAssessmentStatus", "New",
                "inProgressStatus", "New",
                "completedStatus", "Done",
                "remediationStages", List.of(
                        Map.of("id", "dev", "name", "Dev"),
                        Map.of("name", "  QA  "),          // no id → generated; name trimmed
                        Map.of("id", "junk", "name", " "), // blank name → dropped
                        Map.of("id", "prod", "name", "Prod")
                )
        );

        mockMvc.perform(put("/api/v1/config/assessment-workflow")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remediationStages", hasSize(3)))
                .andExpect(jsonPath("$.data.remediationStages[0].id").value("dev"))
                .andExpect(jsonPath("$.data.remediationStages[1].name").value("QA"))
                .andExpect(jsonPath("$.data.remediationStages[1].id").isNotEmpty())
                .andExpect(jsonPath("$.data.remediationStages[2].id").value("prod"));

        // Verify persisted
        mockMvc.perform(get("/api/v1/config/assessment-workflow")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remediationStages", hasSize(3)));
    }

    @Test
    void updateConfig_emptyRemediationStages_fallsBackToDefaults() throws Exception {
        // There must always be a terminal stage to close a vulnerability into.
        Map<String, Object> payload = Map.of(
                "statuses", List.of("New", "Done"),
                "newAssessmentStatus", "New",
                "inProgressStatus", "New",
                "completedStatus", "Done",
                "remediationStages", List.of()
        );

        mockMvc.perform(put("/api/v1/config/assessment-workflow")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remediationStages", hasSize(3)))
                .andExpect(jsonPath("$.data.remediationStages[2].id").value("production"));
    }
}
