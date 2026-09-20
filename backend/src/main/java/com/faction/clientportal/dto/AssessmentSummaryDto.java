package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Aggregate assessment counts for the Assessments nav badge / dashboards, computed
 * server-side from a single grouped query (never materializes the assessment list).
 *
 * <ul>
 *   <li>{@code active} — non-completed, non-deleted assessments (what the badge shows).
 *   <li>{@code total} — all non-deleted assessments.
 *   <li>{@code activeByType} — the active count per assessment type id, for the sidebar's
 *       per-type badges. Derived from the same grouped rows, so each entry counts exactly what
 *       that menu entry opens; a type with nothing active reports zero rather than being absent.
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentSummaryDto {
    private long active;
    private long total;
    private Map<String, Long> activeByType;
}
