package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aggregate assessment counts for the Assessments nav badge / dashboards, computed
 * server-side from a single grouped query (never materializes the assessment list).
 *
 * <ul>
 *   <li>{@code active} — non-completed, non-deleted assessments (what the badge shows).
 *   <li>{@code total} — all non-deleted assessments.
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentSummaryDto {
    private long active;
    private long total;
}
