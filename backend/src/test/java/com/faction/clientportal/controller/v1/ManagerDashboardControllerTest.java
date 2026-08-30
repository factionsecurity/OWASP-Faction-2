package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.Campaign;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Team;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.CampaignRepository;
import com.faction.clientportal.repository.TeamRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ManagerDashboardControllerTest extends TestContainersConfig {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AssessmentRepository assessmentRepository;

    @Autowired
    private VulnerabilityRepository vulnerabilityRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    private String managerToken;

    private Team redTeam;
    private Team blueTeam;
    private Campaign campaignA;
    private User redAssessor;
    private User blueAssessor;
    private Assessment redCompleted;
    private Assessment blueDraft;

    @BeforeEach
    void setUp() {
        vulnerabilityRepository.deleteAll();
        assessmentRepository.deleteAll();
        campaignRepository.deleteAll();

        // The manager token holds ONLY the dashboard permission — proving the
        // dashboard is reachable without any assessments:/vulnerabilities: grants.
        managerToken = jwtService.generateToken("dashboard-manager", List.of(
                new SimpleGrantedAuthority("manager_dashboard:read:all")));

        redTeam = teamRepository.findByName("MD Red Team").orElseGet(() ->
                teamRepository.save(Team.builder().name("MD Red Team").createdAt(LocalDateTime.now()).build()));
        blueTeam = teamRepository.findByName("MD Blue Team").orElseGet(() ->
                teamRepository.save(Team.builder().name("MD Blue Team").createdAt(LocalDateTime.now()).build()));

        redAssessor = userRepository.findByUsername("md-red-assessor").orElseGet(() ->
                userRepository.save(User.builder()
                        .username("md-red-assessor")
                        .firstName("Red").lastName("Assessor")
                        .email("md-red@test.com")
                        .loginOption(LoginOption.NATIVE)
                        .teamIds(List.of(redTeam.getId()))
                        .isInternal(true)
                        .createdAt(LocalDateTime.now())
                        .failedLoginAttempts(0)
                        .build()));
        blueAssessor = userRepository.findByUsername("md-blue-assessor").orElseGet(() ->
                userRepository.save(User.builder()
                        .username("md-blue-assessor")
                        .firstName("Blue").lastName("Assessor")
                        .email("md-blue@test.com")
                        .loginOption(LoginOption.NATIVE)
                        .teamIds(List.of(blueTeam.getId()))
                        .isInternal(true)
                        .createdAt(LocalDateTime.now())
                        .failedLoginAttempts(0)
                        .build()));

        campaignA = campaignRepository.save(Campaign.builder()
                .name("MD Campaign A")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        // Red team's assessment: completed last week, in campaign A,
        // with one CRITICAL opened vulnerability.
        redCompleted = assessmentRepository.save(Assessment.builder()
                .name("Red Completed Assessment")
                .status("COMPLETED")
                .assessorIds(List.of(redAssessor.getId()))
                .campaignId(campaignA.getId())
                .startDate(LocalDateTime.now().minusDays(10))
                .plannedEndDate(LocalDateTime.now().minusDays(5))
                .completedDate(LocalDateTime.now().minusDays(3))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        vulnerabilityRepository.save(Vulnerability.builder()
                .name("Critical Finding")
                .severity(VulnerabilitySeverity.CRITICAL)
                .status("Open")
                .assessmentId(redCompleted.getId())
                .openedAt(LocalDateTime.now().minusDays(3))
                .createdAt(LocalDateTime.now())
                .build());

        // Blue team's assessment: draft, no campaign, one LOW opened vulnerability.
        blueDraft = assessmentRepository.save(Assessment.builder()
                .name("Blue Draft Assessment")
                .status("DRAFT")
                .assessorIds(List.of(blueAssessor.getId()))
                .startDate(LocalDateTime.now().minusDays(2))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        vulnerabilityRepository.save(Vulnerability.builder()
                .name("Low Finding")
                .severity(VulnerabilitySeverity.LOW)
                .status("Open")
                .assessmentId(blueDraft.getId())
                .openedAt(LocalDateTime.now().minusDays(1))
                .createdAt(LocalDateTime.now())
                .build());
        // Same assessment, opened long ago — exercises the vuln-level openedAt
        // range filter independently of the assessment-level date filter.
        vulnerabilityRepository.save(Vulnerability.builder()
                .name("Old Finding")
                .severity(VulnerabilitySeverity.MEDIUM)
                .status("Open")
                .assessmentId(blueDraft.getId())
                .openedAt(LocalDateTime.now().minusDays(40))
                .createdAt(LocalDateTime.now())
                .build());
    }

    @Test
    void summary_ReturnsPeriodCounts() throws Exception {
        mockMvc.perform(get("/api/v1/manager-dashboard/summary")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedAssessments.week").value(1))
                .andExpect(jsonPath("$.data.completedAssessments.allTime").value(1))
                .andExpect(jsonPath("$.data.vulnerabilities.week").value(2))
                .andExpect(jsonPath("$.data.vulnerabilities.allTime").value(3));
    }

    @Test
    void assessments_NoFilters_ReturnsAllSeeded() throws Exception {
        mockMvc.perform(get("/api/v1/manager-dashboard/assessments")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void assessments_TeamFilter_ReturnsOnlyTeamAssessments() throws Exception {
        mockMvc.perform(get("/api/v1/manager-dashboard/assessments")
                        .param("teamId", redTeam.getId())
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].assessment.name").value("Red Completed Assessment"))
                .andExpect(jsonPath("$.data[0].teamNames[0]").value("MD Red Team"));
    }

    @Test
    void assessments_CampaignFilter_ReturnsOnlyCampaignAssessments() throws Exception {
        mockMvc.perform(get("/api/v1/manager-dashboard/assessments")
                        .param("campaignId", campaignA.getId())
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].assessment.name").value("Red Completed Assessment"));
    }

    @Test
    void assessments_SeverityFilter_ReturnsOnlyAssessmentsWithMatchingVulns() throws Exception {
        mockMvc.perform(get("/api/v1/manager-dashboard/assessments")
                        .param("severities", "CRITICAL")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].assessment.name").value("Red Completed Assessment"));
    }

    @Test
    void assessments_CombinedFilters_Narrow() throws Exception {
        // Campaign A + blue team = no overlap
        mockMvc.perform(get("/api/v1/manager-dashboard/assessments")
                        .param("campaignId", campaignA.getId())
                        .param("teamId", blueTeam.getId())
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void vulnerabilities_NoFilters_ReturnsAllOpenedAcrossAssessments() throws Exception {
        mockMvc.perform(get("/api/v1/manager-dashboard/vulnerabilities")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    void vulnerabilities_SeverityFilter_ReturnsOnlyMatching() throws Exception {
        mockMvc.perform(get("/api/v1/manager-dashboard/vulnerabilities")
                        .param("severities", "LOW")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Low Finding"))
                .andExpect(jsonPath("$.data[0].assessmentName").value("Blue Draft Assessment"));
    }

    @Test
    void vulnerabilities_DateRange_ExcludesVulnsOpenedOutsideRange() throws Exception {
        // Range from 3 days ago: the blue assessment (startDate 2 days ago) stays in
        // the assessment set, but its "Old Finding" (opened 40 days ago) is excluded
        // by the vulnerability-level openedAt filter. The red assessment (startDate
        // 10 days ago) drops out at the assessment level, taking its CRITICAL along.
        mockMvc.perform(get("/api/v1/manager-dashboard/vulnerabilities")
                        .param("startDateFrom", LocalDateTime.now().minusDays(3).toString())
                        .param("startDateTo", LocalDateTime.now().toString())
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Low Finding"));
    }

    @Test
    void stats_ReturnsBreakdowns() throws Exception {
        mockMvc.perform(get("/api/v1/manager-dashboard/stats")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.severityBreakdown.CRITICAL").value(1))
                .andExpect(jsonPath("$.data.severityBreakdown.LOW").value(1))
                .andExpect(jsonPath("$.data.severityBreakdown.MEDIUM").value(1))
                .andExpect(jsonPath("$.data.statusBreakdown.COMPLETED").value(1))
                .andExpect(jsonPath("$.data.statusBreakdown.DRAFT").value(1))
                .andExpect(jsonPath("$.data.totalAssessments").value(2))
                .andExpect(jsonPath("$.data.totalVulnerabilities").value(3))
                .andExpect(jsonPath("$.data.completedByAssessor[0].assessorName").value("Red Assessor"))
                .andExpect(jsonPath("$.data.completedByAssessor[0].count").value(1));
    }

    @Test
    void vulnerabilityDetail_ReturnsVulnWithParentAssessment() throws Exception {
        String vulnId = vulnerabilityRepository.findByAssessmentIdAndDeletedAtIsNull(redCompleted.getId())
                .get(0).getId();

        mockMvc.perform(get("/api/v1/manager-dashboard/vulnerabilities/" + vulnId)
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vulnerability.name").value("Critical Finding"))
                .andExpect(jsonPath("$.data.assessment.name").value("Red Completed Assessment"));
    }

    @Test
    void exportAssessmentsCsv_ReturnsCsvWithFilteredRows() throws Exception {
        mockMvc.perform(get("/api/v1/manager-dashboard/export/assessments.csv")
                        .param("campaignId", campaignA.getId())
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Red Completed Assessment")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Blue Draft Assessment"))));
    }

    @Test
    void exportVulnerabilitiesCsv_ReturnsCsvRows() throws Exception {
        mockMvc.perform(get("/api/v1/manager-dashboard/export/vulnerabilities.csv")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Critical Finding")))
                .andExpect(content().string(containsString("Low Finding")));
    }

    @Test
    void dashboard_WithAssessmentPermsButNoDashboardPerm_ReturnsForbidden() throws Exception {
        // Full assessment/vulnerability read access does NOT grant the dashboard —
        // the gate is genuinely independent.
        String assessorToken = jwtService.generateToken("md-not-manager", List.of(
                new SimpleGrantedAuthority("assessments:read:all"),
                new SimpleGrantedAuthority("vulnerabilities:read:all")));

        mockMvc.perform(get("/api/v1/manager-dashboard/summary")
                        .header("Authorization", "Bearer " + assessorToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/manager-dashboard/assessments")
                        .header("Authorization", "Bearer " + assessorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void dashboard_ManagerOnlyToken_SeesEverything() throws Exception {
        // A token holding ONLY manager_dashboard:read:all sees all seeded data —
        // the org-wide view is intentional (no :org/:owned scoping applies).
        mockMvc.perform(get("/api/v1/manager-dashboard/assessments")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(greaterThanOrEqualTo(2)));
    }
}
