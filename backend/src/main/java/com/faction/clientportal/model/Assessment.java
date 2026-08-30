package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assessment entity representing an actual assessment conducted for an application.
 * Snapshots the report template's field definitions at creation time to maintain
 * data integrity even if the template is later modified or deleted.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "assessments", indexes = {
    @Index(name = "idx_applications_id", columnList = "application_id"),
    @Index(name = "idx_assessments_assessmenttypeid", columnList = "assessment_type_id"),
    @Index(name = "idx_assessments_organizationid", columnList = "organization_id"),
    @Index(name = "idx_assessments_teamid", columnList = "team_id"),
    @Index(name = "idx_assessments_reporttemplateid", columnList = "report_template_id"),
    @Index(name = "idx_assessments_status", columnList = "status"),
    @Index(name = "idx_assessments_app_type_status", columnList = "application_id, assessment_type_id, status"),
    @Index(name = "idx_assessments_org_status", columnList = "organization_id, status"),
    @Index(name = "idx_assessments_campaignid", columnList = "campaign_id")
})
public class Assessment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /**
     * Name of this assessment instance
     */
    private String name;

    /**
     * Reference to the Application being assessed
     */
    private String applicationId;

    /**
     * Reference to the AssessmentType
     */
    private String assessmentTypeId;

    /**
     * Reference to the Organization that owns this assessment
     */
    private String organizationId;

    /**
     * Optional reference to a Campaign (filter tag)
     */
    private String campaignId;
    /*
     * Reference to the Team assigned to conduct this assessment
     */
    private String teamId;

    // Template reference and snapshot

    /**
     * Reference to the ReportTemplate used
     */
    private String reportTemplateId;

    /**
     * Version of the template at the time of assessment creation
     */
    private Integer reportTemplateVersion;

    /**
     * Snapshot of template name (for display even if template deleted)
     */
    private String templateName;

    /**
     * Snapshot of template CSS (for report generation)
     */
    @Column(columnDefinition = "TEXT")
    private String templateCss;

    /**
     * Snapshot of the template's report font (for report generation)
     */
    private String templateFont;

    /**
     * Snapshot of template scoring type (NATIVE, CVSS_31, CVSS_40)
     */
    private String scoringType;

    /**
     * Reference to template file ID (shared with template, stored in S3/MinIO)
     */
    private String templateFileId;

    // Snapshotted field definitions (immutable after creation)

    /**
     * Snapshot of the template's sections at creation time.
     */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> sections = new ArrayList<>();

    /**
     * Snapshot of the template's field definitions at creation time.
     * This ensures assessments remain valid even if template is modified/deleted.
     */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<UserDefinedField> fieldDefinitions = new ArrayList<>();

    /**
     * User-entered values for the fields.
     * Map key is the field's ID, value is the user's input.
     */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> fieldValues = new HashMap<>();

    // Assessment metadata

    /**
     * Current status of the assessment (free-form string; see AssessmentWorkflowConfig)
     */
    @Builder.Default
    private String status = "DRAFT";

    /**
     * User ID of the assessor conducting this assessment (legacy - use assessorIds)
     */
    private String assessorId;

    /**
     * List of assessor user IDs for this assessment (replaces single assessorId)
     */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> assessorIds = new ArrayList<>();

    /**
     * User ID of the engagement manager
     */
    private String engagementManagerId;

    /**
     * User ID of the remediation manager
     */
    private String remediationManagerId;

    /**
     * Date the assessment was started/conducted (legacy - use startDate)
     */
    private LocalDateTime assessmentDate;

    /**
     * Scheduled start date for the assessment
     */
    private LocalDateTime startDate;

    /**
     * Planned end date for the assessment
     */
    private LocalDateTime plannedEndDate;

    /**
     * Date the assessment was submitted for peer review
     */
    private LocalDateTime peerReviewedAt;

    /**
     * Date the assessment was completed
     */
    private LocalDateTime completedDate;

    /**
     * Scope of the assessment (markdown format)
     */
    @Column(columnDefinition = "TEXT")
    private String scope;

    /**
     * Engagement-specific URLs (test environments, staging, etc.)
     */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<EngagementUrl> engagementUrls = new ArrayList<>();

    /**
     * Stakeholders for this specific assessment
     */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Stakeholder> stakeholders = new ArrayList<>();

    // Generated report tracking

    /**
     * S3/MinIO file ID reference for the generated report file
     */
    private String generatedReportFileId;

    /**
     * Timestamp when the report was last generated
     */
    private LocalDateTime reportGeneratedAt;

    /**
     * Password for the encrypted PDF report variant, stored AES-GCM encrypted
     * via {@link com.faction.clientportal.service.EncryptionService}.
     * Generated once per assessment and reused across regenerations.
     */
    @Column(length = 512)
    private String reportPasswordEncrypted;

    // Audit fields
    private String createdBy;
    private String lastUpdatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Files attached to this assessment.
     * Only metadata is stored here; actual content lives in MinIO/S3.
     */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<AssessmentFile> attachments = new ArrayList<>();

    /**
     * Soft delete timestamp
     */
    private LocalDateTime deletedAt;

    /**
     * Peer review sub-status (independent of the main AssessmentStatus).
     */
    @Builder.Default
    private AssessmentPeerReviewStatus peerReviewStatus = AssessmentPeerReviewStatus.IN_PROGRESS;

    /**
     * ID of the currently in-flight PeerReview document (null when not locked).
     */
    private String activePeerReviewId;

    /**
     * ID of the successor assessment that was automatically created by the yearly
     * assessment scheduler. Null until a successor has been auto-scheduled.
     */
    private String autoScheduledSuccessorId;
}
