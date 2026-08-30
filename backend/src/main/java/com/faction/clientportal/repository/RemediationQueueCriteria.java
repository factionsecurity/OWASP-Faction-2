package com.faction.clientportal.repository;

import lombok.Builder;

import java.util.Collection;

/**
 * Resolved filter inputs for {@link VulnerabilityRepositoryCustom#listRemediationDue}. The service
 * resolves scope (org / owned apps) and combines it with the request filters, so the repository just
 * builds SQL. Mirrors {@link VulnerabilitySearchCriteria}, whose filters the remediation queue's
 * header shares — minus {@code includeClosed}, which the queue defines away (a closed vulnerability
 * is never a queue row on its own).
 *
 * <p>{@code statuses} matches the <em>vulnerability's</em> status on both row types — on a retest row
 * that is the status of the vuln being retested, matching what the queue's Status column shows. A
 * null status matches the literal {@code "None"}, as it does on the vulnerabilities list.
 *
 * <p>{@code rowType} narrows to one half of the queue — {@code "VULNERABILITY"} or {@code "RETEST"};
 * it has no equivalent on the vulnerabilities list, which only ever holds one kind of row.
 *
 * <p>{@code includeCompletedRetests} widens the retest half to finished retests (PASSED / FAILED)
 * as well as the open ones. Off by default, because the queue is a worklist: a retest that has been
 * verified is history, not work. Cancelled retests stay out either way — a cancelled retest is
 * neither outstanding work nor a result to report on.
 *
 * <p>{@code teamIds} is the team scope resolved from {@code vulnerabilities:read:team} — the row's
 * assessment must belong to one of those teams. Never empty; an empty team set means "see nothing"
 * and the service short-circuits before calling.
 *
 * <p>{@code assessorId} is the assigned scope resolved from {@code vulnerabilities:read:assessment}
 * when the caller may read only their own assessments — the row's assessment must list them as an
 * assessor.
 *
 * <p>Every field is optional: {@code null} (or an empty {@code applicationIds} / {@code statuses} /
 * {@code severityOrdinals} / {@code organizationIds}) = filter not applied. {@code applicationIds} being non-null-but-empty is the owned-scope "no apps →
 * match nothing" case and the service short-circuits before calling.
 */
@Builder
public record RemediationQueueCriteria(
        String search,
        Collection<Integer> severityOrdinals,
        Collection<String> organizationIds,
        Collection<String> applicationIds,
        Collection<String> teamIds,
        String assessorId,
        String assessmentId,
        Collection<String> statuses,
        String rowType,
        boolean includeCompletedRetests
) {}
