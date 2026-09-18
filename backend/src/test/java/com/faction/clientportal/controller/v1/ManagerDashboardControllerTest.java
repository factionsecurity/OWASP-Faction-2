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
import com.faction.clientportal.repository.AssessmentWorkflowRepository;
import com.faction.clientportal.repository.CampaignRepository;
import com.faction.clientportal.repository.TeamRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.service.JwtService;
import com.faction.clientportal.testsupport.TestWorkflows;
import org.junit.jupiter.api.AfterEach;
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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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

    @Autowired
    private AssessmentWorkflowRepository workflowRepository;

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
                .status("Completed")
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

        // Same assessment, opened long ago: exercises the finding-level openedAt filter on an
        // assessment that is finished, so the row is not already excluded for being in progress.
        vulnerabilityRepository.save(Vulnerability.builder()
                .name("Ancient Finding")
                .severity(VulnerabilitySeverity.INFORMATIONAL)
                .status("Open")
                .assessmentId(redCompleted.getId())
                .openedAt(LocalDateTime.now().minusDays(40))
                .createdAt(LocalDateTime.now())
                .build());

        // Blue team's assessment: draft, no campaign, one LOW opened vulnerability.
        blueDraft = assessmentRepository.save(Assessment.builder()
                .name("Blue Draft Assessment")
                .status("New")
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

    @AfterEach
    void removeSecondWorkflow() {
        workflowRepository.deleteById(TestWorkflows.SECOND_ID);
    }

    @Test
    void summary_ReturnsPeriodCounts() throws Exception {
        mockMvc.perform(get("/api/v1/manager-dashboard/summary")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedAssessments.week").value(1))
                .andExpect(jsonPath("$.data.completedAssessments.allTime").value(1))
                // Only the red assessment is completed, so only its CRITICAL counts. The blue
                // assessment's two findings have an openedAt but their assessment is still "New".
                .andExpect(jsonPath("$.data.vulnerabilities.week").value(1))
                .andExpect(jsonPath("$.data.vulnerabilities.allTime").value(2));
    }

    /**
     * The blue assessment is still "New", yet both its findings carry an openedAt — the shape an
     * import or a hand-edited opened date leaves behind. Counting on openedAt alone would report
     * them as delivered work; only the completed red assessment's finding may count.
     */
    @Test
    void summary_CountsOnlyFindingsFromCompletedAssessments() throws Exception {
        mockMvc.perform(get("/api/v1/manager-dashboard/summary")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vulnerabilities.week").value(1))
                .andExpect(jsonPath("$.data.vulnerabilities.allTime").value(2));
    }

    /**
     * Reopening is the case an openedAt test cannot catch: the findings were opened when the
     * assessment completed and keep that timestamp forever, so only the assessment's current
     * status can tell us the work is back in progress.
     */
    @Test
    void summary_ExcludesFindingsFromAnAssessmentThatWasReopened() throws Exception {
        redCompleted.setStatus("New");
        assessmentRepository.save(redCompleted);

        mockMvc.perform(get("/api/v1/manager-dashboard/summary")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vulnerabilities.week").value(0))
                .andExpect(jsonPath("$.data.vulnerabilities.allTime").value(0));
    }

    /** Each assessment is judged by its own workflow's completed status, not a shared name. */
    @Test
    void summary_JudgesCompletionByTheAssessmentsOwnWorkflow() throws Exception {
        TestWorkflows.saveSecondWorkflow(workflowRepository);
        // "Signed Off" completes the second workflow; "Completed" does not. The red assessment keeps
        // the status that completes the DEFAULT workflow, so moving it here must stop it counting.
        redCompleted.setWorkflowId(TestWorkflows.SECOND_ID);
        assessmentRepository.save(redCompleted);

        mockMvc.perform(get("/api/v1/manager-dashboard/summary")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vulnerabilities.allTime").value(0));

        redCompleted.setStatus("Signed Off");
        assessmentRepository.save(redCompleted);

        mockMvc.perform(get("/api/v1/manager-dashboard/summary")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vulnerabilities.allTime").value(2));
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

    /**
     * The dashboard's date boxes are one window over an assessment's activity, not a filter on its
     * start date. Most assessments carry no start date at all, so matching only on that column hid
     * finished work the summary cards were counting.
     */
    @Test
    void assessments_DateWindow_MatchesAnUndatedAssessmentByItsCompletedDate() throws Exception {
        assessmentRepository.save(Assessment.builder()
                .name("Undated But Completed")
                .status("Completed")
                .completedDate(LocalDateTime.now().minusDays(1))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        mockMvc.perform(get("/api/v1/manager-dashboard/assessments")
                        .param("startDateFrom", LocalDateTime.now().minusDays(3).toString())
                        .param("startDateTo", LocalDateTime.now().toString())
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].assessment.name", hasItem("Undated But Completed")));
    }

    @Test
    void assessments_DateWindow_MatchesAnUndatedAssessmentByItsPlannedEndDate() throws Exception {
        assessmentRepository.save(Assessment.builder()
                .name("Undated But Ending")
                .status("New")
                .plannedEndDate(LocalDateTime.now().minusDays(1))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        mockMvc.perform(get("/api/v1/manager-dashboard/assessments")
                        .param("startDateFrom", LocalDateTime.now().minusDays(3).toString())
                        .param("startDateTo", LocalDateTime.now().toString())
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].assessment.name", hasItem("Undated But Ending")));
    }

    /** An assessment carrying no dates at all falls in no window, so a date filter never hides it. */
    @Test
    void assessments_DateWindow_AlwaysIncludesAnAssessmentWithNoDatesAtAll() throws Exception {
        assessmentRepository.save(Assessment.builder()
                .name("No Dates At All")
                .status("New")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        mockMvc.perform(get("/api/v1/manager-dashboard/assessments")
                        .param("startDateFrom", LocalDateTime.now().minusDays(3).toString())
                        .param("startDateTo", LocalDateTime.now().toString())
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].assessment.name", hasItem("No Dates At All")));
    }

    /** A dated assessment whose every date sits outside the window still drops out. */
    @Test
    void assessments_DateWindow_ExcludesAnAssessmentWhoseDatesAllFallOutside() throws Exception {
        assessmentRepository.save(Assessment.builder()
                .name("Long Finished")
                .status("Completed")
                .startDate(LocalDateTime.now().minusDays(400))
                .plannedEndDate(LocalDateTime.now().minusDays(380))
                .completedDate(LocalDateTime.now().minusDays(370))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        mockMvc.perform(get("/api/v1/manager-dashboard/assessments")
                        .param("startDateFrom", LocalDateTime.now().minusDays(3).toString())
                        .param("startDateTo", LocalDateTime.now().toString())
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].assessment.name", not(hasItem("Long Finished"))));
    }

    /**
     * With no column picked the repository's own order applies — newest first. The old default
     * sorted by start date with nulls last, so an assessment created today with no start date sat
     * behind every dated assessment however old, which is where the newest work actually lives.
     */
    @Test
    void assessments_WithNoSortRequested_PutsTheNewestFirstEvenWithNoStartDate() throws Exception {
        assessmentRepository.save(Assessment.builder()
                .name("Newest Undated")
                .status("New")
                // A minute ahead of the fixture's rows, so "newest" is unambiguous in the assertion.
                .createdAt(LocalDateTime.now().plusMinutes(1))
                .updatedAt(LocalDateTime.now())
                .build());

        mockMvc.perform(get("/api/v1/manager-dashboard/assessments")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].assessment.name").value("Newest Undated"));
    }

    /**
     * Ranking findings by severity tier, not by a total. Six lows outweigh one critical on any
     * additive score (6×2 against 5), and on real data that pushed assessments with no critical at
     * all to the top of the list. Criticals and highs are the work that has to be addressed.
     */
    @Test
    void assessments_SortedByFindings_RanksOneCriticalAboveAnyNumberOfLows() throws Exception {
        Assessment noisy = assessmentRepository.save(Assessment.builder()
                .name("Many Low Findings")
                .status("Completed")
                .startDate(LocalDateTime.now().minusDays(9))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        for (int i = 0; i < 6; i++) {
            vulnerabilityRepository.save(Vulnerability.builder()
                    .name("Low " + i)
                    .severity(VulnerabilitySeverity.LOW)
                    .status("Open")
                    .assessmentId(noisy.getId())
                    .openedAt(LocalDateTime.now().minusDays(2))
                    .createdAt(LocalDateTime.now())
                    .build());
        }

        mockMvc.perform(get("/api/v1/manager-dashboard/assessments")
                        .param("sort", "findings,desc")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                // redCompleted carries the only CRITICAL in the fixture.
                .andExpect(jsonPath("$.data[0].assessment.name").value("Red Completed Assessment"))
                .andExpect(jsonPath("$.data[1].assessment.name").value("Blue Draft Assessment"));
    }

    /**
     * What the "Show Incomplete Only" filter sends. Completion is each assessment's own workflow's
     * completed status, so this drops the finished work and leaves what is still being worked on.
     */
    @Test
    void assessments_ShowCompletedFalse_ReturnsOnlyWorkStillInProgress() throws Exception {
        mockMvc.perform(get("/api/v1/manager-dashboard/assessments")
                        .param("showCompleted", "false")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].assessment.name").value("Blue Draft Assessment"));
    }

    /** Each assessment is judged by its own workflow, so "incomplete" is not a shared status name. */
    @Test
    void assessments_ShowCompletedFalse_JudgesCompletionByTheAssessmentsOwnWorkflow() throws Exception {
        TestWorkflows.saveSecondWorkflow(workflowRepository);
        // Two assessments, the same status, different workflows: "Completed" finishes the default
        // workflow but not the second, whose completed status is "Signed Off". The filter must keep
        // one and drop the other — asserting only that the second-workflow one survives would pass
        // just as well with no filtering at all.
        redCompleted.setWorkflowId(TestWorkflows.SECOND_ID);
        assessmentRepository.save(redCompleted);
        assessmentRepository.save(Assessment.builder()
                .name("Default Workflow Completed")
                .status("Completed")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        mockMvc.perform(get("/api/v1/manager-dashboard/assessments")
                        .param("showCompleted", "false")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].assessment.name", hasItem("Red Completed Assessment")))
                .andExpect(jsonPath("$.data[*].assessment.name", not(hasItem("Default Workflow Completed"))));
    }

    /**
     * The past-due badge the table draws under an assessment's name reads this flag, so the rows
     * have to carry it. redCompleted is the control: its planned end date passed days ago too, but
     * it is finished, so it must not be flagged.
     */
    @Test
    void assessments_FlagAnOverdueUnfinishedAssessmentAsPastDue() throws Exception {
        assessmentRepository.save(Assessment.builder()
                .name("Overdue Assessment")
                .status("New")
                .plannedEndDate(LocalDateTime.now().minusDays(2))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        mockMvc.perform(get("/api/v1/manager-dashboard/assessments")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.assessment.name == 'Overdue Assessment')].assessment.isPastDue",
                        contains(true)))
                .andExpect(jsonPath("$.data[?(@.assessment.name == 'Red Completed Assessment')].assessment.isPastDue",
                        contains(false)));
    }

    @Test
    void vulnerabilities_NoFilters_ReturnsAllOpenedAcrossAssessments() throws Exception {
        // Both of the completed assessment's findings; Blue Draft's two are still in progress.
        mockMvc.perform(get("/api/v1/manager-dashboard/vulnerabilities")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    /**
     * The tab lists delivered findings, so it spans finished assessments only — the same rule as
     * the severity breakdown beside it, which otherwise disagreed with the list it sits above.
     * Blue Draft is still "New" yet its findings carry an openedAt, the shape an import or a
     * reopened assessment leaves behind.
     */
    @Test
    void vulnerabilities_ExcludeFindingsFromAnAssessmentStillInProgress() throws Exception {
        mockMvc.perform(get("/api/v1/manager-dashboard/vulnerabilities")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].assessmentName", not(hasItem("Blue Draft Assessment"))));
    }

    @Test
    void vulnerabilities_SeverityFilter_ReturnsOnlyMatching() throws Exception {
        mockMvc.perform(get("/api/v1/manager-dashboard/vulnerabilities")
                        .param("severities", "CRITICAL")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Critical Finding"))
                .andExpect(jsonPath("$.data[0].assessmentName").value("Red Completed Assessment"));
    }

    @Test
    void vulnerabilities_DateRange_ExcludesVulnsOpenedOutsideRange() throws Exception {
        // Range from 4 days ago: the red assessment stays in the set on its completed date (3 days
        // ago), but its "Ancient Finding" (opened 40 days ago) is dropped by the finding-level
        // openedAt filter, leaving only the CRITICAL opened 3 days ago.
        mockMvc.perform(get("/api/v1/manager-dashboard/vulnerabilities")
                        .param("startDateFrom", LocalDateTime.now().minusDays(4).toString())
                        .param("startDateTo", LocalDateTime.now().toString())
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Critical Finding"));
    }

    @Test
    void stats_ReturnsBreakdowns() throws Exception {
        mockMvc.perform(get("/api/v1/manager-dashboard/stats")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.severityBreakdown.CRITICAL").value(1))
                // The blue assessment's LOW and MEDIUM are findings on work still in progress, so
                // they are absent from the severity breakdown entirely.
                .andExpect(jsonPath("$.data.severityBreakdown.LOW").doesNotExist())
                .andExpect(jsonPath("$.data.severityBreakdown.MEDIUM").doesNotExist())
                // The assessment breakdowns still span every filtered assessment, open or not.
                .andExpect(jsonPath("$.data.statusBreakdown.Completed").value(1))
                .andExpect(jsonPath("$.data.statusBreakdown.New").value(1))
                .andExpect(jsonPath("$.data.totalAssessments").value(2))
                .andExpect(jsonPath("$.data.severityBreakdown.INFORMATIONAL").value(1))
                .andExpect(jsonPath("$.data.totalVulnerabilities").value(2))
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
                .andExpect(content().string(containsString("Ancient Finding")));
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

    @Test
    void stats_countsCompletedAssessmentsByEachAssessmentsOwnWorkflow() throws Exception {
        TestWorkflows.saveSecondWorkflow(workflowRepository);
        assessmentRepository.save(Assessment.builder()
                .name("Blue Signed Off Assessment")
                .workflowId(TestWorkflows.SECOND_ID)
                .status("Signed Off")
                .assessorIds(List.of(blueAssessor.getId()))
                .startDate(LocalDateTime.now().minusDays(4))
                .completedDate(LocalDateTime.now().minusDays(1))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        assessmentRepository.save(Assessment.builder()
                .name("Blue Second Signed Off Assessment")
                .workflowId(TestWorkflows.SECOND_ID)
                .status("Signed Off")
                .assessorIds(List.of(blueAssessor.getId()))
                .startDate(LocalDateTime.now().minusDays(6))
                .completedDate(LocalDateTime.now().minusDays(2))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        // "Completed" is Default Workflow's completed status, not the second workflow's: still open.
        assessmentRepository.save(Assessment.builder()
                .name("Blue Second Workflow Open Assessment")
                .workflowId(TestWorkflows.SECOND_ID)
                .status("Completed")
                .assessorIds(List.of(blueAssessor.getId()))
                .startDate(LocalDateTime.now().minusDays(4))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        mockMvc.perform(get("/api/v1/manager-dashboard/stats")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedByAssessor.length()").value(2))
                .andExpect(jsonPath("$.data.completedByAssessor[?(@.assessorName == 'Blue Assessor')].count")
                        .value(contains(2)))
                .andExpect(jsonPath("$.data.completedByAssessor[?(@.assessorName == 'Red Assessor')].count")
                        .value(contains(1)));
    }
}
