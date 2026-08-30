package com.faction.clientportal.dto;

import com.faction.clientportal.model.ApplicationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationDto {

    private String id;
    private String appId;
    private String name;
    private String description;

    // Application URLs
    private List<ApplicationUrlDto> urls;

    // Stakeholders
    private List<StakeholderDto> stakeHolders;

    // Technologies
    private List<String> technologies;

    // Application Owner
    private AppOwnerDto appOwner;

    // Legacy owner fields (for backward compatibility)
    private String ownerName;
    private String ownerEmail;

    // Application Status
    private ApplicationStatus status;

    // Organization relationship
    private String organizationId;

    /** Optional division within the organization; attribution only, not an access boundary. */
    private String subOrganizationId;

    // Region
    private String region;

    // Assessment-related fields
    private String applicationType;
    private String assessmentFrequency;
    private Integer customFrequencyMonths;
    private LocalDateTime lastAssessmentDate;

    // Custom fields
    private List<UserDefinedFieldDto> fieldDefinitions;
    private Map<String, String> fieldValues;

    // Assigned users
    private List<AssignedUserDto> assignedUsers;

    // Chat between the assessment team and application stakeholders/owners
    private List<ApplicationCommentDto> comments;

    /** Usernames following the discussion; everyone here is notified on a new comment. */
    private List<String> subscribers;

    // Audit fields
    private String createdBy;
    private String lastUpdatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Open, non-informational finding count for this application (the applications-list
     * "Open Issues" column). Populated only on the paginated list path via one batched
     * grouped query for the page; null elsewhere.
     */
    private Long openIssueCount;
}
