package com.faction.clientportal.dto;

import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentPeerReviewStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Full DTO for Assessment with all details
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentDto {

    private String id;
    private String name;
    private String applicationId;
    private String appId; // Human-readable application id, for frontend display
    private String applicationName; // Display name for frontend
    private String assessmentTypeId;
    private String assessmentTypeName; // Display name for frontend
    private String organizationId;
    private String campaignId;
    private String campaignName; // Display name for frontend
    private String teamId;
    private String teamName; // Display name for frontend

    // Template information
    private String reportTemplateId;
    private Integer reportTemplateVersion;
    private String templateName;
    private String templateCss;
    private String templateFont;
    private String templateFileId;
    private String scoringType;

    // Sections and field definitions
    @Builder.Default
    private List<String> sections = new ArrayList<>();

    @Builder.Default
    private List<UserDefinedFieldDto> fieldDefinitions = new ArrayList<>();

    @Builder.Default
    private Map<String, String> fieldValues = new HashMap<>();

    // Assessment metadata
    private String status;
    private AssessmentPeerReviewStatus peerReviewStatus;
    private String activePeerReviewId;
    private String assessorId; // Legacy field
    @Builder.Default
    private List<String> assessorIds = new ArrayList<>();
    @Builder.Default
    private List<String> assessorNames = new ArrayList<>(); // Display names for frontend
    @Builder.Default
    private List<String> assessorEmails = new ArrayList<>(); // Emails for frontend
    private String engagementManagerId;
    private String engagementManagerName;
    private String engagementManagerEmail;
    private String remediationManagerId;
    private LocalDateTime assessmentDate; // Legacy field
    private LocalDateTime startDate;
    private LocalDateTime plannedEndDate;
    private LocalDateTime peerReviewedAt;
    private LocalDateTime completedDate;
    private String scope;
    @Builder.Default
    private List<EngagementUrlDto> engagementUrls = new ArrayList<>();
    @Builder.Default
    private List<StakeholderDto> stakeholders = new ArrayList<>();
    private Boolean isPastDue; // Computed field

    // Attachments (metadata only — no file content)
    @Builder.Default
    private List<AssessmentFileDto> attachments = new ArrayList<>();

    // Vulnerability counts by severity
    private VulnerabilitySummary vulnerabilitySummary;

    // Generated report
    private String generatedReportFileId;
    private LocalDateTime reportGeneratedAt;

    // Audit fields
    private String createdBy;
    private String lastUpdatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VulnerabilitySummary {
        private long critical;
        private long high;
        private long medium;
        private long low;
        private long informational;
    }

    /**
     * Convert from entity to DTO
     */
    public static AssessmentDto fromEntity(Assessment entity) {
        if (entity == null) {
            return null;
        }
        return AssessmentDto.builder()
            .id(entity.getId())
            .name(entity.getName())
            .applicationId(entity.getApplicationId())
            .assessmentTypeId(entity.getAssessmentTypeId())
            .organizationId(entity.getOrganizationId())
            .campaignId(entity.getCampaignId())
            .teamId(entity.getTeamId())
            .reportTemplateId(entity.getReportTemplateId())
            .reportTemplateVersion(entity.getReportTemplateVersion())
            .templateName(entity.getTemplateName())
            .templateCss(entity.getTemplateCss())
            .templateFont(entity.getTemplateFont())
            .templateFileId(entity.getTemplateFileId())
            .scoringType(entity.getScoringType())
            .sections(entity.getSections() != null ? new ArrayList<>(entity.getSections()) : new ArrayList<>())
            .fieldDefinitions(entity.getFieldDefinitions() != null
                ? entity.getFieldDefinitions().stream()
                    .map(UserDefinedFieldDto::fromEntity)
                    .collect(Collectors.toList())
                : new ArrayList<>())
            .fieldValues(entity.getFieldValues() != null ? new HashMap<>(entity.getFieldValues()) : new HashMap<>())
            .status(entity.getStatus())
            .peerReviewStatus(entity.getPeerReviewStatus())
            .activePeerReviewId(entity.getActivePeerReviewId())
            .assessorId(entity.getAssessorId())
            .assessorIds(entity.getAssessorIds() != null ? new ArrayList<>(entity.getAssessorIds()) : new ArrayList<>())
            .engagementManagerId(entity.getEngagementManagerId())
            .remediationManagerId(entity.getRemediationManagerId())
            .assessmentDate(entity.getAssessmentDate())
            .startDate(entity.getStartDate())
            .plannedEndDate(entity.getPlannedEndDate())
            .peerReviewedAt(entity.getPeerReviewedAt())
            .completedDate(entity.getCompletedDate())
            .scope(entity.getScope())
            .attachments(entity.getAttachments() != null
                ? entity.getAttachments().stream()
                    .map(AssessmentFileDto::fromEntity)
                    .collect(Collectors.toList())
                : new ArrayList<>())
            .engagementUrls(entity.getEngagementUrls() != null
                ? entity.getEngagementUrls().stream()
                    .map(EngagementUrlDto::fromEntity)
                    .collect(Collectors.toList())
                : new ArrayList<>())
            .stakeholders(entity.getStakeholders() != null
                ? entity.getStakeholders().stream()
                    .map(StakeholderDto::fromEntity)
                    .collect(Collectors.toList())
                : new ArrayList<>())
            .isPastDue(false) // computed by AssessmentService using workflow config
            .generatedReportFileId(entity.getGeneratedReportFileId())
            .reportGeneratedAt(entity.getReportGeneratedAt())
            .createdBy(entity.getCreatedBy())
            .lastUpdatedBy(entity.getLastUpdatedBy())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .deletedAt(entity.getDeletedAt())
            .build();
    }
}
