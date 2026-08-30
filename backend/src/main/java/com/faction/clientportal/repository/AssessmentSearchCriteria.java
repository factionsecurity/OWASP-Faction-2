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
 * <p>{@code statuses} is the multi-select status filter (empty or null → no filter), separate
 * from the single {@code status} the older callers pass; both are ANDed when both are set.
 *
 * <p>{@code restrictAssessmentIds} narrows to a pre-resolved id set — how filters that live in
 * another table (currently "has an unfinished survey") are applied. Null → not applied; empty →
 * match nothing, since a filter that resolved to no assessments must not fall through to "all".
 *
 * <p>{@code reopenableSince} is the reopen-window cutoff: with {@code excludeCompleted}, assessments
 * completed after it stay in the queue so the people who can still reopen them can find them.
 * Required whenever {@code excludeCompleted} is set.
 *
 * <p>{@code scopeAssessorId} and {@code scopeTeamIds} carry the caller's mandatory assessment read
 * scope (see {@code AccessScopeService.resolveAssessmentScope}) — distinct from the optional
 * {@code assignedToMe} / {@code assessorId} <em>filters</em>, which the user can clear. An empty
 * {@code scopeTeamIds} means "belongs to no team" and matches nothing.
 */
@Builder
public record AssessmentSearchCriteria(
        String search,
        String applicationId,
        Collection<String> applicationIds,
        String organizationId,
        Collection<String> ownedAppIds,
        String assessmentTypeId,
        String assessorId,
        String status,
        Collection<String> statuses,
        Collection<String> restrictAssessmentIds,
        LocalDateTime startDateFrom,
        LocalDateTime startDateTo,
        LocalDateTime endDateFrom,
        LocalDateTime endDateTo,
        boolean pastDue,
        boolean excludeCompleted,
        LocalDateTime reopenableSince,
        boolean assignedToMe,
        String currentUserId,
        Collection<String> teamMemberIds,
        String scopeAssessorId,
        Collection<String> scopeTeamIds,
        String campaignId,
        Collection<Integer> severityOrdinals,
        Collection<String> completedStatuses,
        LocalDateTime now
) {}
