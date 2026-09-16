package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentWorkflowRepository;
import com.faction.clientportal.service.JwtService;
import com.faction.clientportal.testsupport.TestWorkflows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Completion is per workflow, so no client can decide it by comparing status text: the row says
 * whether it is completed, and the search can ask for only completed work. Default Workflow completes
 * at "Completed"; the second workflow completes at "Signed Off" and calls its working status
 * "Executing", so a client using one workflow's names against the other would get it wrong.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssessmentCompletedFlagTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private AssessmentWorkflowRepository workflowRepository;

    private String token;

    @BeforeEach
    void setUp() {
        clean();
        token = jwtService.generateToken("completed-flag-admin",
                List.of(new SimpleGrantedAuthority("super_admin")));
        workflowRepository.save(AssessmentWorkflow.defaultWorkflowBuilder().build());
        TestWorkflows.saveSecondWorkflow(workflowRepository);
    }

    @AfterEach
    void clean() {
        assessmentRepository.deleteAll();
        workflowRepository.deleteAll();
    }

    private void assessment(String name, String workflowId, String status) {
        assessmentRepository.save(Assessment.builder()
                .name(name).applicationId("app").assessmentTypeId("t").organizationId("org")
                .workflowId(workflowId).status(status)
                .createdAt(LocalDateTime.now()).build());
    }

    @Test
    void eachRowSaysWhetherItIsCompletedByItsOwnWorkflow() throws Exception {
        // "Completed" completes Default Workflow but means nothing on the second workflow, whose
        // statuses are its own; "Signed Off" completes the second workflow only.
        assessment("Default done", AssessmentWorkflow.DEFAULT_ID, "Completed");
        assessment("Default working", AssessmentWorkflow.DEFAULT_ID, "Testing");
        assessment("Second done", TestWorkflows.SECOND_ID, secondWorkflowCompletedStatus());
        assessment("Second working", TestWorkflows.SECOND_ID, secondWorkflowWorkingStatus());

        // .value(contains(x)) rather than .value(List.of(x)): with a filter (indefinite) path,
        // Spring's JsonPathExpectationsHelper re-reads the result into the expected value's exact
        // runtime type when the classes differ, and List.of(...)'s runtime class is a JDK-internal
        // ImmutableCollections type that the JsonPath mapping provider cannot construct — it silently
        // maps to null. The Hamcrest matcher form (already used the same way in WorkflowControllerTest)
        // compares against the raw evaluated list instead of attempting that conversion.
        mockMvc.perform(get("/api/v1/assessments?size=50&sort=name,asc")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.name == 'Default done')].completed").value(contains(true)))
                .andExpect(jsonPath("$.data[?(@.name == 'Default working')].completed").value(contains(false)))
                .andExpect(jsonPath("$.data[?(@.name == 'Second done')].completed").value(contains(true)))
                .andExpect(jsonPath("$.data[?(@.name == 'Second working')].completed").value(contains(false)));
    }

    @Test
    void onlyCompletedReturnsEveryWorkflowsFinishedWorkAndNothingElse() throws Exception {
        assessment("Default done", AssessmentWorkflow.DEFAULT_ID, "Completed");
        assessment("Default working", AssessmentWorkflow.DEFAULT_ID, "Testing");
        assessment("Second done", TestWorkflows.SECOND_ID, secondWorkflowCompletedStatus());
        assessment("Second working", TestWorkflows.SECOND_ID, secondWorkflowWorkingStatus());

        mockMvc.perform(get("/api/v1/assessments?size=50&sort=name,asc&onlyCompleted=true")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("Default done"))
                .andExpect(jsonPath("$.data[1].name").value("Second done"));
    }

    @Test
    void onlyCompletedWinsWhenShowCompletedIsAlsoSentFalse() throws Exception {
        // A client that always sends its current showCompleted=false toggle alongside an
        // explicit onlyCompleted=true request must still get completed work back — onlyCompleted
        // is the more specific ask and should not be cancelled out by the coexisting false flag.
        assessment("Default done", AssessmentWorkflow.DEFAULT_ID, "Completed");
        assessment("Default working", AssessmentWorkflow.DEFAULT_ID, "Testing");

        mockMvc.perform(get("/api/v1/assessments?size=50&showCompleted=false&onlyCompleted=true")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Default done"));
    }

    @Test
    void theDefaultListStillHidesCompletedWorkFromEveryWorkflow() throws Exception {
        assessment("Default done", AssessmentWorkflow.DEFAULT_ID, "Completed");
        assessment("Second done", TestWorkflows.SECOND_ID, secondWorkflowCompletedStatus());
        assessment("Second working", TestWorkflows.SECOND_ID, secondWorkflowWorkingStatus());

        mockMvc.perform(get("/api/v1/assessments?size=50&showCompleted=false")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Second working"));
    }

    private String secondWorkflowCompletedStatus() {
        return TestWorkflows.secondWorkflow().getCompletedStatus();
    }

    private String secondWorkflowWorkingStatus() {
        return TestWorkflows.secondWorkflow().getInProgressStatus();
    }
}
