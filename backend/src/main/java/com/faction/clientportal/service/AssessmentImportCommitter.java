package com.faction.clientportal.service;

import com.faction.clientportal.dto.CreateAssessmentRequest;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.Campaign;
import com.faction.clientportal.repository.CampaignRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a resolved CSV import in one transaction: the new campaigns, then every assessment
 * (creating each new application with the first row that names it). Any failure rolls the whole
 * batch back. It is its own bean so the transaction applies through the Spring proxy, and it
 * announces nothing — the caller does that once the batch has committed.
 *
 * <p>The persistence context is flushed and cleared every {@value #FLUSH_EVERY} rows. Otherwise
 * it grows by an assessment, a notebook node and an application per row, and every query a row
 * runs dirty-checks all of them first, so a large file slows down quadratically. Nothing here
 * keeps a managed entity across a clear: rows carry ids, and the returned assessments are only
 * read for plain columns.
 */
@Service
@RequiredArgsConstructor
public class AssessmentImportCommitter {

    static final int FLUSH_EVERY = 100;

    private final AssessmentService assessmentService;
    private final CampaignRepository campaignRepository;
    private final WorkflowCatalogService workflowCatalogService;

    @PersistenceContext
    private EntityManager entityManager;

    public record Outcome(List<Assessment> assessments, List<String> createdApplications,
                          List<String> createdCampaigns) {
    }

    @Transactional
    public Outcome commit(AssessmentImportPlan plan, String userId) {
        Map<String, String> campaignIds = new HashMap<>();
        List<String> createdCampaigns = new ArrayList<>();
        plan.pendingCampaigns().forEach((key, name) -> {
            LocalDateTime now = LocalDateTime.now();
            Campaign campaign = campaignRepository.save(Campaign.builder()
                    .name(name).createdAt(now).updatedAt(now).build());
            campaignIds.put(key, campaign.getId());
            createdCampaigns.add(name);
        });

        WorkflowCatalog catalog = workflowCatalogService.load();
        Map<String, String> applicationIds = new HashMap<>();
        List<String> createdApplications = new ArrayList<>();
        List<Assessment> created = new ArrayList<>();
        for (AssessmentImportPlan.PlannedRow row : plan.rows()) {
            CreateAssessmentRequest request = row.request();
            if (row.pendingCampaignKey() != null) {
                request.setCampaignId(campaignIds.get(row.pendingCampaignKey()));
            }
            String pendingApplication = row.pendingApplicationKey();
            if (pendingApplication != null && applicationIds.containsKey(pendingApplication)) {
                // An earlier row already created it; point at it rather than creating a twin.
                request.setApplicationId(applicationIds.get(pendingApplication));
            }

            Assessment assessment = assessmentService.persistNewAssessment(request, userId, catalog);

            if (pendingApplication != null && !applicationIds.containsKey(pendingApplication)) {
                applicationIds.put(pendingApplication, assessment.getApplicationId());
                createdApplications.add(plan.pendingApplications().get(pendingApplication).name());
            }
            created.add(assessment);
            if (created.size() % FLUSH_EVERY == 0) {
                entityManager.flush();
                entityManager.clear();
            }
        }
        return new Outcome(created, createdApplications, createdCampaigns);
    }
}
