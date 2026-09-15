package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.edition.CommunityOnly;
import com.faction.clientportal.edition.EnterpriseOnly;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentType;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.RemediationStage;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.model.VulnerabilityStageCompletion;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.AssessmentWorkflowRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.repository.VulnerabilityStageCompletionRepository;
import com.faction.clientportal.service.JwtService;
import com.faction.clientportal.testsupport.TestWorkflows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Moving an assessment to another workflow: its status maps by role, its findings keep built-in and shared
 * statuses (anything else becomes Open) with due dates recalculated under the target, and its stage
 * completions follow the stage with the same name. A dry run reports all of that and writes nothing.
 * Moving away from Default Workflow needs Custom Workflows; moving back works everywhere.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssessmentWorkflowMoveTest extends TestContainersConfig {

    private static final String SECOND = TestWorkflows.SECOND_ID;
    private static final LocalDateTime OPENED = LocalDateTime.of(2099, 1, 1, 9, 0);

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private AssessmentWorkflowRepository workflowRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private AssessmentTypeRepository assessmentTypeRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private VulnerabilityStageCompletionRepository stageCompletionRepository;

    private String adminToken;
    private String editorToken;

    @BeforeEach
    void setUp() {
        clean();
        adminToken = jwtService.generateToken("move-admin", List.of(new SimpleGrantedAuthority("super_admin")));
        editorToken = jwtService.generateToken("move-editor", List.of(new SimpleGrantedAuthority("assessments:edit:all")));
        workflowRepository.save(AssessmentWorkflow.defaultWorkflowBuilder()
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        TestWorkflows.saveSecondWorkflow(workflowRepository);
    }

    @AfterEach
    void clean() {
        stageCompletionRepository.deleteAll();
        vulnerabilityRepository.deleteAll();
        assessmentRepository.deleteAll();
        assessmentTypeRepository.deleteAll();
        workflowRepository.deleteAll();
    }

    @EnterpriseOnly
    @Test
    void movingToAnotherWorkflowMapsTheAssessmentAndItsFindings() throws Exception {
        Assessment assessment = assessment("default", "Testing");
        Vulnerability open = finding(assessment, "Open");
        Vulnerability deferred = finding(assessment, "Deferred");

        move(assessment, SECOND, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fromStatus").value("Testing"))
                .andExpect(jsonPath("$.data.toStatus").value("Fieldwork"))
                .andExpect(jsonPath("$.data.findingCount").value(2))
                .andExpect(jsonPath("$.data.findingStatusChanges[0].from").value("Deferred"))
                .andExpect(jsonPath("$.data.findingStatusChanges[0].to").value("Open"))
                .andExpect(jsonPath("$.data.findingStatusChanges[0].count").value(1))
                .andExpect(jsonPath("$.data.dueDateChanges").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.applied").value(false));
        assertThat(assessmentRepository.findById(assessment.getId()).orElseThrow().getWorkflowId()).isEqualTo("default");
        assertThat(vulnerabilityRepository.findById(deferred.getId()).orElseThrow().getStatus()).isEqualTo("Deferred");
        // The dry run reports a due-date change (second workflow's HIGH SLA is 14 days) but must not
        // apply it — a finding's stored due date, not only its status, is untouched by a preview.
        assertThat(vulnerabilityRepository.findById(open.getId()).orElseThrow().getDueAt()).isNull();

        move(assessment, SECOND, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(true));
        Assessment moved = assessmentRepository.findById(assessment.getId()).orElseThrow();
        assertThat(moved.getWorkflowId()).isEqualTo(SECOND);
        assertThat(moved.getStatus()).isEqualTo("Fieldwork");
        assertThat(vulnerabilityRepository.findById(deferred.getId()).orElseThrow().getStatus()).isEqualTo("Open");
        // Second workflow's HIGH SLA is 14 days, counted from the original opened date.
        assertThat(vulnerabilityRepository.findById(open.getId()).orElseThrow().getDueAt()).isEqualTo(OPENED.plusDays(14));
    }

    @EnterpriseOnly
    @Test
    void stageCompletionsFollowTheStageWithTheSameName() throws Exception {
        AssessmentWorkflow third = TestWorkflows.secondWorkflow();
        third.setId("third-workflow");
        third.setName("Third Workflow");
        third.setRemediationStages(new ArrayList<>(List.of(new RemediationStage("third-qa", "QA"))));
        workflowRepository.save(third);
        Assessment assessment = assessment(SECOND, "Fieldwork");
        Vulnerability finding = finding(assessment, "Open");
        VulnerabilityStageCompletion qa = completion(finding, "second-qa");
        VulnerabilityStageCompletion live = completion(finding, "second-live");

        move(assessment, "third-workflow", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remappedStageCompletions").value(1))
                .andExpect(jsonPath("$.data.unmappedStageCompletions").value(1));
        // A preview reports what would remap but must not touch a stage completion's stored stage id.
        assertThat(stageCompletionRepository.findById(qa.getId()).orElseThrow().getStageId()).isEqualTo("second-qa");
        assertThat(stageCompletionRepository.findById(live.getId()).orElseThrow().getStageId()).isEqualTo("second-live");

        move(assessment, "third-workflow", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toStatus").value("Fieldwork"))
                .andExpect(jsonPath("$.data.remappedStageCompletions").value(1))
                .andExpect(jsonPath("$.data.unmappedStageCompletions").value(1));

        assertThat(stageCompletionRepository.findById(qa.getId()).orElseThrow().getStageId()).isEqualTo("third-qa");
        assertThat(stageCompletionRepository.findById(live.getId()).orElseThrow().getStageId()).isEqualTo("second-live");
    }

    @CommunityOnly
    @Test
    void movingAwayFromDefaultWorkflowIsNotInTheOpenSourceEdition() throws Exception {
        Assessment assessment = assessment("default", "Testing");

        move(assessment, SECOND, true)
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.feature").value("custom_workflows"));
        assertThat(assessmentRepository.findById(assessment.getId()).orElseThrow().getWorkflowId()).isEqualTo("default");
    }

    @Test
    void movingBackToDefaultWorkflowWorksInEveryEdition() throws Exception {
        Assessment assessment = assessment(SECOND, "Signed Off");

        move(assessment, "default", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toStatus").value("Completed"));

        Assessment moved = assessmentRepository.findById(assessment.getId()).orElseThrow();
        assertThat(moved.getWorkflowId()).isEqualTo("default");
        assertThat(moved.getStatus()).isEqualTo("Completed");
    }

    @Test
    void unknownArchivedOrCurrentTargetsAndCallersWithoutConfigWriteAreRefused() throws Exception {
        AssessmentWorkflow archived = TestWorkflows.secondWorkflow();
        archived.setId("archived-workflow");
        archived.setName("Archived Workflow");
        archived.setArchived(true);
        workflowRepository.save(archived);
        Assessment assessment = assessment(SECOND, "Draft");

        move(assessment, "no-such-workflow", true).andExpect(status().isBadRequest());
        move(assessment, SECOND, true).andExpect(status().isBadRequest());
        move(assessment, "archived-workflow", true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.violations[0].kind").value("TARGET_ARCHIVED"));
        mockMvc.perform(post("/api/v1/assessments/" + assessment.getId() + "/move-workflow")
                        .header("Authorization", "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowId\":\"default\",\"dryRun\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void changingTheTypeWithMoveToTypeWorkflowMovesTheAssessmentOntoTheTypesWorkflow() throws Exception {
        AssessmentType onDefault = assessmentTypeRepository.save(AssessmentType.builder()
                .name("Web " + UUID.randomUUID()).description("Web").workflowId("default")
                .createdAt(LocalDateTime.now()).build());
        Assessment assessment = assessment(SECOND, "Signed Off");
        String body = "{\"assessmentTypeId\":\"" + onDefault.getId() + "\",\"moveToTypeWorkflow\":true}";

        mockMvc.perform(put("/api/v1/assessments/" + assessment.getId())
                        .header("Authorization", "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertThat(assessmentRepository.findById(assessment.getId()).orElseThrow().getAssessmentTypeId()).isEqualTo("type-1");

        mockMvc.perform(put("/api/v1/assessments/" + assessment.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        Assessment moved = assessmentRepository.findById(assessment.getId()).orElseThrow();
        assertThat(moved.getAssessmentTypeId()).isEqualTo(onDefault.getId());
        assertThat(moved.getWorkflowId()).isEqualTo("default");
        assertThat(moved.getStatus()).isEqualTo("Completed");
    }

    private ResultActions move(Assessment assessment, String workflowId, boolean dryRun) throws Exception {
        return mockMvc.perform(post("/api/v1/assessments/" + assessment.getId() + "/move-workflow")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflowId\":\"" + workflowId + "\",\"dryRun\":" + dryRun + "}"));
    }

    private Assessment assessment(String workflowId, String status) {
        return assessmentRepository.save(Assessment.builder()
                .name("Move " + UUID.randomUUID()).assessmentTypeId("type-1").organizationId("org-1")
                .workflowId(workflowId).status(status)
                .startDate(LocalDateTime.now().minusDays(1))
                .createdAt(LocalDateTime.now()).build());
    }

    private Vulnerability finding(Assessment assessment, String status) {
        // Opened in 2099, so the every-minute past-due job never touches it.
        return vulnerabilityRepository.save(Vulnerability.builder()
                .name("Finding " + UUID.randomUUID()).assessmentId(assessment.getId())
                .severity(VulnerabilitySeverity.HIGH).status(status).openedAt(OPENED)
                .createdAt(LocalDateTime.now()).build());
    }

    private VulnerabilityStageCompletion completion(Vulnerability finding, String stageId) {
        return stageCompletionRepository.save(VulnerabilityStageCompletion.builder()
                .id(UUID.randomUUID().toString()).vulnerabilityId(finding.getId())
                .stageId(stageId).completedAt(LocalDateTime.now()).build());
    }
}
