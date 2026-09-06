package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Whether one candidate assessor is already booked across a proposed assessment window.
 *
 * <p>Carries the clashing assessments rather than only a flag, so the scheduler can see
 * what someone is busy with — "busy" alone invites the question and does not answer it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssessorAvailabilityDto {

    private String userId;

    /** True when {@link #conflicts} is non-empty; sent explicitly so the client need not infer it. */
    private boolean busy;

    private List<ConflictingAssessment> conflicts;

    /**
     * The overlapping assessment, trimmed to what a tooltip needs. Deliberately not an
     * {@link AssessmentDto}: this endpoint answers a scheduling question for every
     * candidate at once, and returning whole assessments would send the same records
     * repeatedly, once per assessor they share.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConflictingAssessment {
        private String id;
        private String name;
        private LocalDateTime startDate;
        private LocalDateTime plannedEndDate;
    }
}
