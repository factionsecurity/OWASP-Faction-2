package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Request DTO for asking which of a set of candidate assessors are already booked
 * across a proposed assessment window.
 *
 * <p>Distinct from {@link ConflictCheckRequest}, which asks the narrower question of
 * whether the assessors already chosen collide with anything. This one is asked about
 * everyone who could be chosen, so the scheduler can see who is free before choosing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssessorAvailabilityRequest {
    /**
     * ID of the assessment being scheduled, or null when creating a new one. An
     * assessment never counts as a conflict with itself — without this, editing the
     * dates of an existing assessment shows every assessor it already has as busy.
     */
    private String assessmentId;

    /** The users to report on. */
    private List<String> assessorIds;

    /** Start of the proposed window. */
    private LocalDateTime startDate;

    /** Planned end of the proposed window. */
    private LocalDateTime endDate;
}
