package com.faction.clientportal.service;

import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentType;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.RemediationStage;
import com.faction.clientportal.repository.CompletedStatusFilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * A snapshot of every assessment workflow, loaded once per request or job run by
 * {@link WorkflowCatalogService#load()} and passed to the code that needs it. Never cached across
 * requests, so it cannot go stale across backend instances.
 *
 * <p>A missing or unknown workflow id resolves to Default Workflow. Lists that span workflows merge
 * statuses and stages by exact name: Default Workflow first, then the other workflows by name,
 * each in its own order, keeping the first occurrence.
 */
public final class WorkflowCatalog {

    /** Each workflow's configured completed status, index-aligned, for binding as SQL arrays. */
    public record CompletedPairs(List<String> workflowIds, List<String> completedStatuses) {
    }

    private static final Comparator<AssessmentWorkflow> BY_NAME = Comparator
            .comparing((AssessmentWorkflow w) -> Objects.requireNonNullElse(w.getName(), ""),
                    String.CASE_INSENSITIVE_ORDER)
            .thenComparing(w -> Objects.requireNonNullElse(w.getId(), ""));

    private final AssessmentWorkflow defaultWorkflow;
    private final List<AssessmentWorkflow> ordered;
    private final Map<String, AssessmentWorkflow> byId;

    private WorkflowCatalog(AssessmentWorkflow defaultWorkflow, List<AssessmentWorkflow> ordered) {
        this.defaultWorkflow = defaultWorkflow;
        this.ordered = ordered;
        Map<String, AssessmentWorkflow> ids = new LinkedHashMap<>();
        for (AssessmentWorkflow workflow : ordered) {
            ids.putIfAbsent(workflow.getId(), workflow);
        }
        this.byId = ids;
    }

    /**
     * @throws IllegalArgumentException when no workflow has id "default" and none is marked as
     *         the default
     */
    public static WorkflowCatalog of(Collection<AssessmentWorkflow> workflows) {
        AssessmentWorkflow defaultWorkflow = workflows.stream()
                .filter(w -> AssessmentWorkflow.DEFAULT_ID.equals(w.getId()))
                .findFirst()
                .orElseGet(() -> workflows.stream()
                        .filter(AssessmentWorkflow::isDefaultWorkflow)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No default workflow among " + workflows.size() + " workflow(s)")));
        List<AssessmentWorkflow> ordered = new ArrayList<>();
        ordered.add(defaultWorkflow);
        workflows.stream().filter(w -> w != defaultWorkflow).sorted(BY_NAME).forEach(ordered::add);
        return new WorkflowCatalog(defaultWorkflow, List.copyOf(ordered));
    }

    public AssessmentWorkflow defaultWorkflow() {
        return defaultWorkflow;
    }

    /** Default Workflow first, then the others by name. */
    public List<AssessmentWorkflow> workflows(boolean includeArchived) {
        return ordered.stream().filter(w -> includeArchived || !w.isArchived()).toList();
    }

    public AssessmentWorkflow forId(String workflowId) {
        return workflowId == null ? defaultWorkflow : byId.getOrDefault(workflowId, defaultWorkflow);
    }

    public AssessmentWorkflow forAssessment(Assessment assessment) {
        return assessment == null ? defaultWorkflow : forId(assessment.getWorkflowId());
    }

    public AssessmentWorkflow forType(AssessmentType type) {
        return type == null ? defaultWorkflow : forId(type.getWorkflowId());
    }

    public List<String> allStatusNames(boolean includeArchived) {
        return merged(includeArchived, AssessmentWorkflow::getStatuses);
    }

    /** The workflows' additional vulnerability statuses; the built-in ones are implicit. */
    public List<String> allVulnerabilityStatusNames(boolean includeArchived) {
        return merged(includeArchived, AssessmentWorkflow::getVulnerabilityStatuses);
    }

    public List<String> allStageNames(boolean includeArchived) {
        return merged(includeArchived, w -> w.getRemediationStages() == null ? null
                : w.getRemediationStages().stream().map(RemediationStage::getName).toList());
    }

    /** Every workflow's completed status, archived workflows included (their assessments still finish). */
    public CompletedPairs completedPairs() {
        List<String> ids = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        for (AssessmentWorkflow workflow : ordered) {
            String completed = workflow.getCompletedStatus();
            if (completed != null && !completed.isBlank()) {
                ids.add(workflow.getId());
                statuses.add(completed);
            }
        }
        return new CompletedPairs(List.copyOf(ids), List.copyOf(statuses));
    }

    /** Every workflow's completed status, as the filter SQL that spans workflows binds. */
    public CompletedStatusFilter completedStatusFilter() {
        CompletedPairs pairs = completedPairs();
        return new CompletedStatusFilter(pairs.workflowIds(), pairs.completedStatuses(),
                ordered.stream().map(AssessmentWorkflow::getId).toList(), defaultWorkflow.getId());
    }

    private List<String> merged(boolean includeArchived, Function<AssessmentWorkflow, List<String>> names) {
        Set<String> merged = new LinkedHashSet<>();
        for (AssessmentWorkflow workflow : workflows(includeArchived)) {
            List<String> values = names.apply(workflow);
            if (values == null) continue;
            for (String value : values) {
                if (value != null) merged.add(value);
            }
        }
        return List.copyOf(merged);
    }
}
