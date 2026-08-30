package com.faction.clientportal.dto;

import com.faction.clientportal.model.Retest;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class RetestDto {
    private String id;
    private String vulnerabilityId;
    private String vulnerabilityName;
    private String vulnerabilitySeverity;
    private String assessmentId;
    private String assessmentName;
    private String applicationId;
    private String applicationName;
    private LocalDateTime scheduledStartDate;
    private LocalDateTime scheduledEndDate;
    private LocalDateTime closedDate;
    /** Username of whoever verified the result; null on a retest that is still open. */
    private String completedBy;
    /** Display name for {@link #completedBy}, resolved by the service. */
    private String completedByName;
    private List<String> assignedAssessorIds;
    private List<String> assignedAssessorNames;
    private String status;
    private String result;
    private String comment;
    private String scope;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static RetestDto fromEntity(Retest r) {
        return RetestDto.builder()
                .id(r.getId())
                .vulnerabilityId(r.getVulnerabilityId())
                .assessmentId(r.getAssessmentId())
                .applicationId(r.getApplicationId())
                .scheduledStartDate(r.getScheduledStartDate())
                .scheduledEndDate(r.getScheduledEndDate())
                .closedDate(r.getClosedDate())
                .completedBy(r.getCompletedBy())
                .assignedAssessorIds(r.getAssignedAssessorIds())
                .status(r.getStatus())
                .result(r.getResult())
                .comment(r.getComment())
                .scope(r.getScope())
                .createdBy(r.getCreatedBy())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .build();
    }
}
