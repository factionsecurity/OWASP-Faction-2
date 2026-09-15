package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.edition.CommunityOnly;
import com.faction.clientportal.edition.EnterpriseOnly;
import com.faction.clientportal.model.AssessmentType;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.AssessmentWorkflowRepository;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An assessment type names the workflow new assessments of it are created under. Default Workflow when not
 * given; any other workflow only where Custom Workflows is available; never an archived or unknown one.
 * Keeping a type on the workflow it already has, or moving it back to Default Workflow, works everywhere.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssessmentTypeWorkflowTest extends TestContainersConfig {

    private static final String SECOND = TestWorkflows.SECOND_ID;

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private AssessmentTypeRepository assessmentTypeRepository;
    @Autowired private AssessmentWorkflowRepository workflowRepository;

    private String token;

    @BeforeEach
    void setUp() {
        clean();
        token = jwtService.generateToken("type-admin", List.of(new SimpleGrantedAuthority("super_admin")));
        TestWorkflows.saveSecondWorkflow(workflowRepository);
    }

    @AfterEach
    void clean() {
        assessmentTypeRepository.deleteAll();
        workflowRepository.deleteAll();
    }

    private static String typeJson(String name, String workflowId) {
        return "{\"name\":\"" + name + "\",\"description\":\"d\",\"active\":true"
                + (workflowId == null ? "" : ",\"workflowId\":\"" + workflowId + "\"") + "}";
    }

    private AssessmentType savedType(String name, String workflowId) {
        return assessmentTypeRepository.save(AssessmentType.builder()
                .name(name).description("d").workflowId(workflowId).createdAt(LocalDateTime.now()).build());
    }

    @Test
    void aTypeCreatedWithoutAWorkflowIsOnDefaultWorkflow() throws Exception {
        mockMvc.perform(post("/api/v1/assessment-types").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(typeJson("Web", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.workflowId").value("default"));
    }

    @EnterpriseOnly
    @Test
    void aTypeCanBeAssignedAnotherWorkflow() throws Exception {
        mockMvc.perform(post("/api/v1/assessment-types").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(typeJson("PCI", SECOND)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.workflowId").value(SECOND));
    }

    @CommunityOnly
    @Test
    void assigningAnotherWorkflowIsNotInTheOpenSourceEdition() throws Exception {
        mockMvc.perform(post("/api/v1/assessment-types").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(typeJson("PCI", SECOND)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.feature").value("custom_workflows"));

        AssessmentType web = savedType("Web", "default");
        mockMvc.perform(put("/api/v1/assessment-types/" + web.getId()).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(typeJson("Web", SECOND)))
                .andExpect(status().isPaymentRequired());
        assertThat(assessmentTypeRepository.findById(web.getId()).orElseThrow().getWorkflowId()).isEqualTo("default");
    }

    @Test
    void keepingTheCurrentWorkflowOrMovingBackToDefaultWorksInEveryEdition() throws Exception {
        AssessmentType pci = savedType("PCI", SECOND);

        mockMvc.perform(put("/api/v1/assessment-types/" + pci.getId()).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(typeJson("PCI Renamed", SECOND)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workflowId").value(SECOND));
        mockMvc.perform(put("/api/v1/assessment-types/" + pci.getId()).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(typeJson("PCI Renamed", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workflowId").value(SECOND));
        mockMvc.perform(put("/api/v1/assessment-types/" + pci.getId()).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(typeJson("PCI Renamed", "default")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workflowId").value("default"));
    }

    @Test
    void anArchivedWorkflowIsRefusedAndAnUnknownOneIsABadRequest() throws Exception {
        AssessmentWorkflow archived = TestWorkflows.secondWorkflow();
        archived.setId("archived-workflow");
        archived.setName("Archived Workflow");
        archived.setArchived(true);
        workflowRepository.save(archived);

        mockMvc.perform(post("/api/v1/assessment-types").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(typeJson("Old", "archived-workflow")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.violations[0].kind").value("TARGET_ARCHIVED"));
        mockMvc.perform(post("/api/v1/assessment-types").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(typeJson("Nowhere", "no-such-workflow")))
                .andExpect(status().isBadRequest());
    }
}
