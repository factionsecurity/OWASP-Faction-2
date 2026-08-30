package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Request DTO for updating an existing assessment
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateAssessmentRequest {

    @Size(max = 255, message = "Assessment name must not exceed 255 characters")
    private String name;

    private String applicationId;

    /**
     * Reassign the assessment to a different assessment type. The report template is tied to the
     * type, so sending this without a matching {@code reportTemplateId} is rejected — see
     * AssessmentService#updateAssessment.
     */
    private String assessmentTypeId;

    private String campaignId;

    private String reportTemplateId;

    private String teamId;

    /**
     * Updated field values
     * Map key is the field ID, value is the updated value
     */
    private Map<String, String> fieldValues;

    private String status;

    private String assessorId; // Legacy field

    private List<String> assessorIds;

    private String engagementManagerId;

    private String remediationManagerId;

    /**
     * When the assessment was completed. Normally stamped by the server the moment the status
     * first becomes a completed one; an importer loading historical assessments sets it explicitly
     * so the record carries the date the testing actually finished. Ignored unless {@code status}
     * is a completed status.
     */
    private LocalDateTime completedDate;

    private LocalDateTime startDate;

    private LocalDateTime plannedEndDate;

    private String scope;

    private List<EngagementUrlDto> engagementUrls;

    private List<StakeholderDto> stakeholders;
}
