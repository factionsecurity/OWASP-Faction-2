package com.faction.clientportal.service;

import com.faction.clientportal.dto.AssessmentImportPreviewDto;
import com.faction.clientportal.dto.CreateAssessmentRequest;

import java.util.List;
import java.util.Map;

/**
 * A fully resolved CSV import: one create request per row, plus the applications and campaigns
 * the batch has to create first. Pending records are keyed so rows naming the same new
 * application or campaign (ignoring case) share one record.
 */
public record AssessmentImportPlan(
        List<PlannedRow> rows,
        Map<String, PendingApplication> pendingApplications,
        Map<String, String> pendingCampaigns,
        AssessmentImportPreviewDto preview) {

    public record PlannedRow(int line, CreateAssessmentRequest request,
                             String pendingApplicationKey, String pendingCampaignKey) {
    }

    public record PendingApplication(String appId, String name) {
    }
}
