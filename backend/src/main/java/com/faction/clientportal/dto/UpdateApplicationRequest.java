package com.faction.clientportal.dto;

import com.faction.clientportal.model.ApplicationStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
public class UpdateApplicationRequest {

    @NotBlank(message = "Application name is required")
    private String name;

    private String appId;

    private String description;

    // Application URLs
    @Valid
    private List<ApplicationUrlDto> urls;

    // Stakeholders
    @Valid
    private List<StakeholderDto> stakeHolders;

    // Technologies
    private List<String> technologies;

    // Application Owner
    @Valid
    private AppOwnerDto appOwner;

    // Legacy owner fields (for backward compatibility)
    private String ownerName;
    @Email(message = "Invalid owner email format")
    private String ownerEmail;

    // Application Status
    private ApplicationStatus status;

    // Organization relationship
    private String organizationId;

    /** Optional division within the organization; attribution only, not an access boundary. */
    private String subOrganizationId;

    // Region
    private String region;

    // Assessment-related fields (optional)
    private String applicationType;
    private String assessmentFrequency;
    private Integer customFrequencyMonths;
    private LocalDateTime lastAssessmentDate;

    private Map<String, String> fieldValues;
}
