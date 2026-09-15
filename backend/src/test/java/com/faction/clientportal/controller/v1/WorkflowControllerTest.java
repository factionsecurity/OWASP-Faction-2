package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.edition.CommunityOnly;
import com.faction.clientportal.edition.EnterpriseOnly;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentType;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.EmailNotificationConfig;
import com.faction.clientportal.model.RemediationStage;
import com.faction.clientportal.model.WorkflowRenameTask;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.AssessmentWorkflowRepository;
import com.faction.clientportal.repository.EmailNotificationConfigRepository;
import com.faction.clientportal.repository.WorkflowRenameTaskRepository;
import com.faction.clientportal.service.JwtService;
import com.faction.clientportal.testsupport.TestWorkflows;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Workflows API: listing (archived hidden unless asked), usage counts for admins, copying a workflow
 * with fresh stage ids and its per-stage email settings, editing, archiving and deleting with their
 * guards, and creating gated behind Custom Workflows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkflowControllerTest extends TestContainersConfig {

    private static final String SECOND = TestWorkflows.SECOND_ID;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private AssessmentWorkflowRepository workflowRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private AssessmentTypeRepository assessmentTypeRepository;
    @Autowired private WorkflowRenameTaskRepository renameTaskRepository;
    @Autowired private EmailNotificationConfigRepository emailConfigRepository;

    private String adminToken;
    private String readerToken;

    @BeforeEach
    void setUp() {
        clean();
        adminToken = jwtService.generateToken("workflow-admin", List.of(new SimpleGrantedAuthority("config:write")));
        readerToken = jwtService.generateToken("workflow-reader", List.of(new SimpleGrantedAuthority("assessments:read:all")));
        workflowRepository.save(AssessmentWorkflow.defaultWorkflowBuilder()
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        TestWorkflows.saveSecondWorkflow(workflowRepository);
    }

    @AfterEach
    void clean() {
        renameTaskRepository.deleteAll();
        assessmentRepository.deleteAll();
        assessmentTypeRepository.deleteAll();
        emailConfigRepository.deleteAll();
        workflowRepository.deleteAll();
    }

    @Test
    void anySignedInUserListsWorkflowsWithDefaultFirstAndArchivedHiddenUnlessAsked() throws Exception {
        AssessmentWorkflow old = TestWorkflows.secondWorkflow();
        old.setId("old-workflow");
        old.setName("Old Workflow");
        old.setArchived(true);
        workflowRepository.save(old);

        mockMvc.perform(get("/api/v1/workflows").header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id").value(contains("default", SECOND)))
                .andExpect(jsonPath("$.data[0].defaultWorkflow").value(true))
                .andExpect(jsonPath("$.data[0].builtInVulnerabilityStatuses").value(hasItem("Past Due")));

        mockMvc.perform(get("/api/v1/workflows").param("includeArchived", "true")
                        .header("Authorization", "Bearer " + readerToken))
                .andExpect(jsonPath("$.data[*].id").value(contains("default", "old-workflow", SECOND)));
    }

    @Test
    void usageCountsNeedConfigWrite() throws Exception {
        assessmentTypeRepository.save(AssessmentType.builder().name("Web " + UUID.randomUUID())
                .description("Web").workflowId(SECOND).createdAt(LocalDateTime.now()).build());
        assessment(SECOND, "Draft");
        assessment(SECOND, "Draft");

        mockMvc.perform(get("/api/v1/workflows/usage").header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/workflows/usage").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.workflowId == 'second-workflow')].assessmentTypeCount").value(contains(1)))
                .andExpect(jsonPath("$.data[?(@.workflowId == 'second-workflow')].assessmentCount").value(contains(2)))
                .andExpect(jsonPath("$.data[?(@.workflowId == 'default')].assessmentCount").value(contains(0)));
    }

    @Test
    void readingAnUnknownWorkflowIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/workflows/no-such").header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isNotFound());
    }

    @EnterpriseOnly
    @Test
    void creatingCopiesTheSourceWithFreshStageIdsAndTheirEmailSettings() throws Exception {
        Map<String, EmailNotificationConfig.EventSettings> events = new HashMap<>();
        events.put("VULNERABILITY_CLOSED:second-qa", EmailNotificationConfig.EventSettings.builder().notifyAssessors(true).build());
        emailConfigRepository.save(EmailNotificationConfig.builder()
                .id(EmailNotificationConfig.SINGLETON_ID).events(events).build());

        String body = mockMvc.perform(post("/api/v1/workflows").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceWorkflowId\":\"second-workflow\",\"name\":\"PCI Workflow\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("PCI Workflow"))
                .andExpect(jsonPath("$.data.defaultWorkflow").value(false))
                .andExpect(jsonPath("$.data.archived").value(false))
                .andExpect(jsonPath("$.data.completedStatus").value("Signed Off"))
                .andExpect(jsonPath("$.data.remediationStages[*].name").value(contains("QA", "Live")))
                .andExpect(jsonPath("$.data.remediationStages[*].id").value(not(hasItem("second-qa"))))
                .andReturn().getResponse().getContentAsString();

        String copiedQaId = objectMapper.readTree(body).at("/data/remediationStages/0/id").asText();
        EmailNotificationConfig config = emailConfigRepository.findById(EmailNotificationConfig.SINGLETON_ID).orElseThrow();
        assertThat(config.settingsFor("VULNERABILITY_CLOSED:" + copiedQaId).isNotifyAssessors()).isTrue();
        assertThat(config.settingsFor("VULNERABILITY_CLOSED:second-qa").isNotifyAssessors()).isTrue();
    }

    @EnterpriseOnly
    @Test
    void creatingWithANameAnotherWorkflowHasIsAConflict() throws Exception {
        mockMvc.perform(post("/api/v1/workflows").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceWorkflowId\":\"default\",\"name\":\"second workflow\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.violations[0].kind").value("NAME_TAKEN"));
    }

    @CommunityOnly
    @Test
    void creatingAWorkflowIsNotInTheOpenSourceEdition() throws Exception {
        mockMvc.perform(post("/api/v1/workflows").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceWorkflowId\":\"default\",\"name\":\"PCI Workflow\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("FEATURE_NOT_LICENSED"))
                .andExpect(jsonPath("$.feature").value("custom_workflows"));
        assertThat(workflowRepository.count()).isEqualTo(2);
    }

    @Test
    void editingAnExistingWorkflowWorksInEveryEdition() throws Exception {
        String request = """
                {"name":"Second Workflow Renamed",
                 "statuses":[{"originalName":"Draft","name":"Draft"},{"originalName":"Scoping","name":"Scoping"},
                             {"originalName":"Fieldwork","name":"On Site"},{"originalName":"Signed Off","name":"Signed Off"}],
                 "newAssessmentStatus":"Draft","inProgressStatus":"On Site","completedStatus":"Signed Off",
                 "statusColors":{},"vulnerabilitySlas":[],
                 "vulnerabilityStatuses":[{"originalName":"Risk Accepted","name":"Risk Accepted"}],
                 "remediationStages":[{"id":"second-qa","name":"QA"},{"id":"second-live","name":"Live"}],
                 "allowSelfPeerReview":true}""";

        mockMvc.perform(put("/api/v1/workflows/" + SECOND).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Second Workflow Renamed"))
                .andExpect(jsonPath("$.data.inProgressStatus").value("On Site"));
    }

    @Test
    void archivingHidesAWorkflowAndIsRefusedForDefaultOrWhileATypeUsesIt() throws Exception {
        mockMvc.perform(post("/api/v1/workflows/default/archive").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.violations[0].kind").value("DEFAULT_WORKFLOW"));

        AssessmentType type = assessmentTypeRepository.save(AssessmentType.builder().name("Web " + UUID.randomUUID())
                .description("Web").workflowId(SECOND).createdAt(LocalDateTime.now()).build());
        mockMvc.perform(post("/api/v1/workflows/" + SECOND + "/archive").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.violations[0].kind").value("USED_BY_ASSESSMENT_TYPES"))
                .andExpect(jsonPath("$.violations[0].count").value(1));

        assessmentTypeRepository.delete(type);
        assessment(SECOND, "Fieldwork"); // an assessment does not block archiving; it keeps working
        mockMvc.perform(post("/api/v1/workflows/" + SECOND + "/archive").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.archived").value(true));
        mockMvc.perform(get("/api/v1/workflows").header("Authorization", "Bearer " + readerToken))
                .andExpect(jsonPath("$.data[*].id").value(contains("default")));

        mockMvc.perform(post("/api/v1/workflows/" + SECOND + "/unarchive").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.archived").value(false));
    }

    @Test
    void deletingIsRefusedForDefaultOrWhileAnythingUsesItAndOtherwiseRemovesItsStageEmailSettings() throws Exception {
        mockMvc.perform(delete("/api/v1/workflows/default").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.violations[0].kind").value("DEFAULT_WORKFLOW"));

        Assessment inUse = assessment(SECOND, "Draft");
        mockMvc.perform(delete("/api/v1/workflows/" + SECOND).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.violations[0].kind").value("USED_BY_ASSESSMENTS"))
                .andExpect(jsonPath("$.violations[0].count").value(1));

        assessmentRepository.delete(inUse);
        Map<String, EmailNotificationConfig.EventSettings> events = new HashMap<>();
        events.put("VULNERABILITY_CLOSED:second-qa", EmailNotificationConfig.EventSettings.builder().notifyAssessors(true).build());
        emailConfigRepository.save(EmailNotificationConfig.builder()
                .id(EmailNotificationConfig.SINGLETON_ID).events(events).build());
        renameTaskRepository.save(WorkflowRenameTask.start(SECOND, "Old", "New"));

        mockMvc.perform(delete("/api/v1/workflows/" + SECOND).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        assertThat(workflowRepository.findById(SECOND)).isEmpty();
        assertThat(renameTaskRepository.findAll()).isEmpty();
        assertThat(emailConfigRepository.findById(EmailNotificationConfig.SINGLETON_ID).orElseThrow().getEvents())
                .doesNotContainKey("VULNERABILITY_CLOSED:second-qa");
    }

    @Test
    void deletingAWorkflowKeepsAnotherWorkflowsSharedStageEmailSettings() throws Exception {
        AssessmentWorkflow reusesDefaultStage = TestWorkflows.secondWorkflow();
        reusesDefaultStage.setId("reuses-default-stage");
        reusesDefaultStage.setName("Reuses Default Stage");
        reusesDefaultStage.setRemediationStages(List.of(new RemediationStage("development", "Development")));
        workflowRepository.save(reusesDefaultStage);

        Map<String, EmailNotificationConfig.EventSettings> events = new HashMap<>();
        events.put("VULNERABILITY_CLOSED:development", EmailNotificationConfig.EventSettings.builder().notifyAssessors(true).build());
        emailConfigRepository.save(EmailNotificationConfig.builder()
                .id(EmailNotificationConfig.SINGLETON_ID).events(events).build());

        mockMvc.perform(delete("/api/v1/workflows/" + reusesDefaultStage.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        assertThat(workflowRepository.findById(reusesDefaultStage.getId())).isEmpty();
        assertThat(emailConfigRepository.findById(EmailNotificationConfig.SINGLETON_ID).orElseThrow().getEvents())
                .containsKey("VULNERABILITY_CLOSED:development");
    }

    @Test
    void writesNeedConfigWrite() throws Exception {
        mockMvc.perform(post("/api/v1/workflows/" + SECOND + "/archive").header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/workflows/" + SECOND).header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isForbidden());
    }

    private Assessment assessment(String workflowId, String status) {
        return assessmentRepository.save(Assessment.builder()
                .name("Api " + UUID.randomUUID()).assessmentTypeId("type-1").organizationId("org-1")
                .workflowId(workflowId).status(status).createdAt(LocalDateTime.now()).build());
    }
}
