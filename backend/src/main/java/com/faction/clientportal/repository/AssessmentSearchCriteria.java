package com.faction.clientportal.repository;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * Resolved filter inputs for {@link AssessmentRepositoryCustom#searchAdvanced}. The service
 * resolves scope (org / owned-app ids), team membership, the completed-status set, and the
 * tri-state booleans into the plain values below, so the repository just builds SQL.
 *
 * <p>Collection-field conventions:
 * <ul>
 *   <li>{@code null} → filter not applied.
 *   <li>empty → match nothing, for the <em>scope</em> collections ({@code ownedAppIds},
 *       {@code teamMemberIds}) — an owned scope resolving to zero apps or a team with no members
 *       excludes everything, preserving the old in-memory {@code contains} behavior.
 *   <li><b>Exception:</b> {@code applicationIds} is a user-supplied multi-select <em>filter</em>,
 *       so empty means "no filter" (show all), not "match nothing".
 * </ul>
 *
 * <p>{@code scopeOrgIds} / {@code scopeAppIds} carry the mandatory membership (ORG) read scope:
 * the organizations the caller belongs to OR the applications their sub-organizations grant, ORed
 * together. Null on both → not applied; non-null and both empty → match nothing.
 *
 * <p>{@code statuses} is the multi-select status filter (empty or null → no filter), separate
 * from the single {@code status} the older callers pass; both are ANDed when both are set.
 *
 * <p>{@code restrictAssessmentIds} narrows to a pre-resolved id set — how filters that live in
 * another table (currently "has an unfinished survey") are applied. Null → not applied; empty →
 * match nothing, since a filter that resolved to no assessments must not fall through to "all".
 *
 * <p>{@code scopeAssessorId} and {@code scopeTeamIds} carry the caller's mandatory assessment read
 * scope (see {@code AccessScopeService.resolveAssessmentScope}) — distinct from the optional
 * {@code assignedToMe} / {@code assessorId} <em>filters</em>, which the user can clear. An empty
 * {@code scopeTeamIds} means "belongs to no team" and matches nothing.
 *
 * @param completed required when {@code pastDue}, {@code excludeCompleted} or {@code onlyCompleted} is set
 */
@Builder
public record AssessmentSearchCriteria(
        String search,
        String applicationId,
        Collection<String> applicationIds,
        String organizationId,
        Collection<String> ownedAppIds,
        Collection<String> scopeOrgIds,
        Collection<String> scopeAppIds,
        String assessmentTypeId,
        /** Multi-select type filter (UI); empty or null → no filter, like {@code applicationIds}. */
        Collection<String> assessmentTypeIds,
        String assessorId,
        String status,
        Collection<String> statuses,
        Collection<String> restrictAssessmentIds,
        LocalDateTime startDateFrom,
        LocalDateTime startDateTo,
        LocalDateTime endDateFrom,
        LocalDateTime endDateTo,
        LocalDateTime completedDateFrom,
        LocalDateTime completedDateTo,
        boolean pastDue,
        boolean excludeCompleted,
        /** Only assessments their own workflow calls completed; the narrow twin of {@code excludeCompleted}. */
        boolean onlyCompleted,
        boolean assignedToMe,
        String currentUserId,
        Collection<String> teamMemberIds,
        String scopeAssessorId,
        Collection<String> scopeTeamIds,
        String campaignId,
        Collection<Integer> severityOrdinals,
        CompletedStatusFilter completed,
        LocalDateTime now
) {}
