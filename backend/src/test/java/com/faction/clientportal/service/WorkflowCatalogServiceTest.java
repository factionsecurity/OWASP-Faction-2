package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.repository.AssessmentWorkflowRepository;
import com.faction.clientportal.testsupport.TestWorkflows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** The catalog is loaded from the database for each use, always with Default Workflow in it. */
@SpringBootTest
@ActiveProfiles("test")
class WorkflowCatalogServiceTest extends TestContainersConfig {

    @Autowired private WorkflowCatalogService catalogService;
    @Autowired private AssessmentWorkflowRepository workflowRepository;

    @BeforeEach
    void setUp() {
        workflowRepository.deleteAll();
    }

    @AfterEach
    void resetWorkflows() {
        workflowRepository.deleteAll();
    }

    @Test
    void loadsEveryStoredWorkflow() {
        workflowRepository.save(AssessmentWorkflow.defaultWorkflowBuilder().completedStatus("Done").build());
        TestWorkflows.saveSecondWorkflow(workflowRepository);

        WorkflowCatalog catalog = catalogService.load();

        assertThat(catalog.defaultWorkflow().getCompletedStatus()).isEqualTo("Done");
        assertThat(catalog.forId(TestWorkflows.SECOND_ID).getCompletedStatus()).isEqualTo("Signed Off");
        assertThat(catalog.workflows(true)).hasSize(2);
    }

    @Test
    void createsDefaultWorkflowWhenItIsMissing() {
        TestWorkflows.saveSecondWorkflow(workflowRepository);

        WorkflowCatalog catalog = catalogService.load();

        assertThat(catalog.defaultWorkflow().getId()).isEqualTo("default");
        assertThat(workflowRepository.findById("default")).isPresent();
        assertThat(catalogService.load().workflows(true)).hasSize(2);
    }

    @Test
    void eachLoadSeesChangesMadeSinceTheLastOne() {
        catalogService.load();
        AssessmentWorkflow stored = workflowRepository.findById("default").orElseThrow();
        stored.setCompletedStatus("Finished");
        workflowRepository.save(stored);

        assertThat(catalogService.load().defaultWorkflow().getCompletedStatus()).isEqualTo("Finished");
    }

    @Test
    void aDefaultRowThatLostItsFlagStillLoads() {
        workflowRepository.save(AssessmentWorkflow.defaultWorkflowBuilder().defaultWorkflow(false).build());

        WorkflowCatalog catalog = catalogService.load();

        assertThat(catalog.defaultWorkflow().getId()).isEqualTo("default");
        assertThat(catalog.workflows(true)).hasSize(1);
    }
}
