package com.faction.clientportal.repository;

import jakarta.persistence.Query;

import java.util.List;
import java.util.Objects;

/**
 * What "completed" means for a query that spans workflows: an assessment is completed when its status
 * is its own workflow's completed status. An assessment on a workflow id that is not in
 * {@code knownWorkflowIds} is judged by Default Workflow's. Bound as arrays, so the SQL text never
 * depends on how many workflows exist.
 *
 * @param workflowIds       workflows that have a completed status, index-aligned with {@code completedStatuses}
 * @param completedStatuses each of those workflows' completed status
 * @param knownWorkflowIds  every workflow id, archived included
 * @param defaultWorkflowId Default Workflow's id
 */
public record CompletedStatusFilter(List<String> workflowIds, List<String> completedStatuses,
                                    List<String> knownWorkflowIds, String defaultWorkflowId) {

    /**
     * True when assessment {@code a} is not completed; a null status is never completed. Bind its
     * parameters with {@link #bind}.
     */
    public static final String NOT_COMPLETED_SQL = """
            NOT EXISTS (SELECT 1
                        FROM unnest(CAST(:doneWorkflowIds AS text[]), CAST(:doneStatuses AS text[])) AS done(wf, status)
                        WHERE done.status = a.status
                          AND done.wf = CASE WHEN a.workflow_id = ANY(CAST(:knownWorkflowIds AS text[]))
                                             THEN a.workflow_id
                                             ELSE CAST(:defaultWorkflowId AS text) END)""";

    public CompletedStatusFilter {
        workflowIds = List.copyOf(workflowIds);
        completedStatuses = List.copyOf(completedStatuses);
        knownWorkflowIds = List.copyOf(knownWorkflowIds);
        Objects.requireNonNull(defaultWorkflowId, "defaultWorkflowId");
        if (workflowIds.size() != completedStatuses.size()) {
            throw new IllegalArgumentException("workflowIds and completedStatuses must be the same length");
        }
    }

    public void bind(Query query) {
        query.setParameter("doneWorkflowIds", workflowIds.toArray(String[]::new));
        query.setParameter("doneStatuses", completedStatuses.toArray(String[]::new));
        query.setParameter("knownWorkflowIds", knownWorkflowIds.toArray(String[]::new));
        query.setParameter("defaultWorkflowId", defaultWorkflowId);
    }
}
