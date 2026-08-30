package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Request DTO for creating a new assessment from a template
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateAssessmentRequest {

    @NotBlank(message = "Assessment name is required")
    @Size(max = 255, message = "Assessment name must not exceed 255 characters")
    private String name;

    private String applicationId;

    private String appId;

    private String applicationName;

    private String campaignId;

    @NotBlank(message = "Assessment type ID is required")
    private String assessmentTypeId;

    @NotBlank(message = "Report template ID is required")
    private String reportTemplateId;

    private String teamId;

    private String assessorId; // Legacy field

    @Builder.Default
    private List<String> assessorIds = new ArrayList<>();

    private String engagementManagerId;

    private String remediationManagerId;

    private LocalDateTime startDate;

    private LocalDateTime plannedEndDate;

    private String scope;

    @Builder.Default
    private List<EngagementUrlDto> engagementUrls = new ArrayList<>();

    @Builder.Default
    private List<StakeholderDto> stakeholders = new ArrayList<>();

    /**
     * Optional initial field values
     * Map key is the field ID, value is the initial value
     */
    @Builder.Default
    private Map<String, String> initialFieldValues = new HashMap<>();
}
