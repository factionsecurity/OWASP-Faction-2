package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.Campaign;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.CampaignRepository;
import com.faction.clientportal.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CampaignControllerTest extends TestContainersConfig {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private AssessmentRepository assessmentRepository;

    @Autowired
    private JwtService jwtService;

    private String managerToken;

    @BeforeEach
    void setUp() {
        assessmentRepository.deleteAll();
        campaignRepository.deleteAll();

        managerToken = jwtService.generateToken("campaign-manager", List.of(
                new SimpleGrantedAuthority("campaigns:read:all"),
                new SimpleGrantedAuthority("campaigns:create:all"),
                new SimpleGrantedAuthority("campaigns:edit:all"),
                new SimpleGrantedAuthority("campaigns:delete:all")));
    }

    private Campaign saveCampaign(String name, boolean isDefault) {
        return campaignRepository.save(Campaign.builder()
                .name(name)
                .isDefault(isDefault)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    @Test
    void createCampaign_WithValidName_ReturnsCreated() throws Exception {
        mockMvc.perform(post("/api/v1/campaigns")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Q3 Campaign\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.name").value("Q3 Campaign"))
                .andExpect(jsonPath("$.data.isDefault").value(false));
    }

    @Test
    void createCampaign_WithDuplicateName_ReturnsBadRequest() throws Exception {
        saveCampaign("Existing", false);

        mockMvc.perform(post("/api/v1/campaigns")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Existing\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listCampaigns_WithSearch_ReturnsMatches() throws Exception {
        saveCampaign("Alpha Campaign", false);
        saveCampaign("Beta Campaign", false);

        mockMvc.perform(get("/api/v1/campaigns")
                        .param("search", "Alpha")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Alpha Campaign"));
    }

    @Test
    void getAllCampaignsUnpaged_ReturnsEveryCampaign() throws Exception {
        saveCampaign("One", false);
        saveCampaign("Two", false);

        mockMvc.perform(get("/api/v1/campaigns/all")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void getCampaignById_Missing_ReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/campaigns/does-not-exist")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateCampaign_Rename_ReturnsUpdated() throws Exception {
        Campaign campaign = saveCampaign("Old Name", false);

        mockMvc.perform(put("/api/v1/campaigns/" + campaign.getId())
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("New Name"));
    }

    @Test
    void updateCampaign_SetDefault_UnsetsPreviousDefault() throws Exception {
        Campaign previousDefault = saveCampaign("Previous Default", true);
        Campaign next = saveCampaign("Next Default", false);

        mockMvc.perform(put("/api/v1/campaigns/" + next.getId())
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isDefault\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDefault").value(true));

        assertFalse(campaignRepository.findById(previousDefault.getId()).orElseThrow().getIsDefault());
        assertTrue(campaignRepository.findById(next.getId()).orElseThrow().getIsDefault());
    }

    @Test
    void deleteCampaign_Unreferenced_Succeeds() throws Exception {
        Campaign campaign = saveCampaign("Deletable", false);

        mockMvc.perform(delete("/api/v1/campaigns/" + campaign.getId())
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());

        assertFalse(campaignRepository.findById(campaign.getId()).isPresent());
    }

    @Test
    void deleteCampaign_ReferencedByAssessment_ReturnsBadRequest() throws Exception {
        Campaign campaign = saveCampaign("In Use", false);
        assessmentRepository.save(Assessment.builder()
                .name("Assessment using campaign")
                .campaignId(campaign.getId())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        mockMvc.perform(delete("/api/v1/campaigns/" + campaign.getId())
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("assigned to one or more assessments")));

        assertTrue(campaignRepository.findById(campaign.getId()).isPresent());
    }

    @Test
    void campaignEndpoints_WithoutCampaignPermission_ReturnForbidden() throws Exception {
        String unrelatedToken = jwtService.generateToken("no-campaign-perms", List.of(
                new SimpleGrantedAuthority("assessments:read:all")));

        mockMvc.perform(get("/api/v1/campaigns")
                        .header("Authorization", "Bearer " + unrelatedToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/campaigns")
                        .header("Authorization", "Bearer " + unrelatedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\"}"))
                .andExpect(status().isForbidden());
    }
}
