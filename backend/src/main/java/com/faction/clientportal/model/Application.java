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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "applications", indexes = {
    @Index(name = "idx_applications_name", columnList = "name"),
    @Index(name = "idx_applications_app_id", columnList = "appId", unique = true)
})
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(unique = true)
    private String appId;

    @Column(nullable = false)
    private String name;

    // Edited via the frontend's RichTextEditor — holds HTML well beyond 255 chars
    @Column(columnDefinition = "TEXT")
    private String description;

    // Application URLs (new)
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<ApplicationUrl> urls = new ArrayList<>();

    // Stakeholders
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Stakeholder> stakeHolders = new ArrayList<>();

    // Technologies used
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> technologies = new ArrayList<>();

    // Application Owner (new - replaces ownerName/ownerEmail)
    @JdbcTypeCode(SqlTypes.JSON)
    private AppOwner appOwner;

    // Legacy owner fields (kept for backward compatibility)
    private String ownerName;
    private String ownerEmail;

    // Application Status (new)
    private ApplicationStatus status;

    // Organization relationship
    private String organizationId;

    /**
     * Optional division within {@link #organizationId} (see SubOrganization). Attribution only —
     * the owning organization, and therefore who can see this application, is unchanged.
     */
    private String subOrganizationId;

    // Region
    @Builder.Default
    private String region = "Global";

    // Assessment-related fields (existing)
    private String applicationType;
    private String assessmentFrequency;
    private Integer customFrequencyMonths;
    private LocalDateTime lastAssessmentDate;

    // Custom field values (keyed by field definition ID)
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> fieldValues = new HashMap<>();

    // Assigned portal users (owners)
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<AssignedUser> assignedUsers = new ArrayList<>();

    // Chat between the assessment team and application stakeholders/owners
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<ApplicationComment> comments = new ArrayList<>();

    /**
     * Usernames following this application's discussion. Everyone here is notified — in app
     * and by email — when a comment is added, and can reply to that email to post back.
     *
     * <p>Mirrors {@code Vulnerability.subscribers}: an explicit list rather than one derived
     * from who has commented, so membership is visible and can be left. Usernames, not ids,
     * matching {@code ApplicationComment.authorId} and the notification pipeline.
     */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> subscribers = new ArrayList<>();

    // Audit fields
    private String createdBy;
    private String lastUpdatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
