package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Request DTO for checking assessment conflicts
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConflictCheckRequest {
    /**
     * ID of the assessment being checked (null for new assessments)
     */
    private String assessmentId;

    /**
     * List of assessor IDs to check for conflicts
     */
    private List<String> assessorIds;

    /**
     * Start date of the assessment
     */
    private LocalDateTime startDate;

    /**
     * Planned end date of the assessment
     */
    private LocalDateTime endDate;
}
