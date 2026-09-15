package com.faction.clientportal.service;

import com.faction.clientportal.dto.workflow.UpdateWorkflowRequest;
import com.faction.clientportal.dto.workflow.UpdateWorkflowRequest.NamedEntry;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.RemediationStage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What an edit changes in a workflow's named rows: which assessment statuses and vulnerability statuses
 * were renamed (old name → new name) or removed, and which remediation stages were removed (id → name).
 * The guards refuse removals of rows still in use, and the renames rewrite the data.
 */
public record WorkflowSettingsChange(
        Map<String, String> renamedStatuses,
        Set<String> removedStatuses,
        Map<String, String> renamedVulnerabilityStatuses,
        Set<String> removedVulnerabilityStatuses,
        Map<String, String> removedStages) {

    /** Validates a tracked edit and works out what it changes. */
    public static WorkflowSettingsChange of(AssessmentWorkflow current, UpdateWorkflowRequest request) {
        List<NamedEntry> statuses = request.getStatuses();
        if (statuses == null || statuses.isEmpty()) {
            throw new IllegalArgumentException("A workflow needs at least one assessment status");
        }
        Set<String> statusNames = names(statuses, "assessment status");
        requireListed(statusNames, request.getNewAssessmentStatus(), "New");
        requireListed(statusNames, request.getInProgressStatus(), "In Progress");
        requireListed(statusNames, request.getCompletedStatus(), "Completed");

        List<NamedEntry> vulnerabilityStatuses = request.getVulnerabilityStatuses() == null
                ? List.of() : request.getVulnerabilityStatuses();
        for (NamedEntry entry : vulnerabilityStatuses) {
            for (String name : new String[]{entry.getOriginalName(), entry.getName()}) {
                if (AssessmentWorkflows.isBuiltInVulnerabilityStatus(trim(name))) {
                    throw new IllegalArgumentException("\"" + trim(name)
                            + "\" is a built-in vulnerability status and cannot be added, renamed or removed");
                }
            }
        }
        names(vulnerabilityStatuses, "vulnerability status");

        if (request.getVulnerabilitySlas() == null) {
            throw new IllegalArgumentException("vulnerabilitySlas is required (send an empty list for no SLAs)");
        }

        List<RemediationStage> stages = request.getRemediationStages();
        if (stages == null || stages.isEmpty()) {
            throw new IllegalArgumentException("A workflow needs at least one remediation stage");
        }
        for (RemediationStage stage : stages) {
            if (stage == null || stage.getName() == null || stage.getName().isBlank()) {
                throw new IllegalArgumentException("A remediation stage name cannot be blank");
            }
        }

        return new WorkflowSettingsChange(
                renames(current.getStatuses(), statuses, "assessment status"),
                removed(current.getStatuses(), statuses),
                renames(current.getVulnerabilityStatuses(), vulnerabilityStatuses, "vulnerability status"),
                removed(current.getVulnerabilityStatuses(), vulnerabilityStatuses),
                removedStages(current.getRemediationStages(), effectiveStages(request.getRemediationStages())));
    }

    /** A save that does not track renames (the Default Workflow alias): any name no longer listed is removed. */
    public static WorkflowSettingsChange untracked(AssessmentWorkflow current, AssessmentWorkflow submitted) {
        return new WorkflowSettingsChange(
                Map.of(),
                missing(current.getStatuses(), submitted.getStatuses()),
                Map.of(),
                missing(current.getVulnerabilityStatuses(), submitted.getVulnerabilityStatuses()),
                removedStages(current.getRemediationStages(), effectiveStages(submitted.getRemediationStages())));
    }

    /** The edit as settings to save: trimmed names, with each renamed status's colour moved to its new name. */
    public static AssessmentWorkflow settingsOf(UpdateWorkflowRequest request, WorkflowSettingsChange change) {
        Map<String, String> colors = request.getStatusColors() == null
                ? new HashMap<>() : new HashMap<>(request.getStatusColors());
        change.renamedStatuses().forEach((from, to) -> {
            if (!colors.containsKey(to) && colors.containsKey(from)) {
                colors.put(to, colors.remove(from));
            } else {
                colors.remove(from);
            }
        });
        return AssessmentWorkflow.builder()
                .statuses(trimmedNames(request.getStatuses()))
                .newAssessmentStatus(trim(request.getNewAssessmentStatus()))
                .inProgressStatus(trim(request.getInProgressStatus()))
                .completedStatus(trim(request.getCompletedStatus()))
                .statusColors(colors)
                .vulnerabilitySlas(request.getVulnerabilitySlas() == null
                        ? new ArrayList<>() : new ArrayList<>(request.getVulnerabilitySlas()))
                .vulnerabilityStatuses(trimmedNames(request.getVulnerabilityStatuses()))
                .remediationStages(request.getRemediationStages() == null
                        ? null : new ArrayList<>(request.getRemediationStages()))
                .allowSelfPeerReview(request.isAllowSelfPeerReview())
                .build();
    }

    private static Set<String> names(List<NamedEntry> entries, String kind) {
        Set<String> seen = new LinkedHashSet<>();
        for (NamedEntry entry : entries) {
            String name = trim(entry.getName());
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("A " + kind + " name cannot be blank");
            }
            if (!seen.add(name)) {
                throw new IllegalArgumentException("Duplicate " + kind + ": \"" + name + "\"");
            }
        }
        return seen;
    }

    private static void requireListed(Set<String> names, String status, String role) {
        if (status == null || !names.contains(status.trim())) {
            throw new IllegalArgumentException("The " + role + " status \"" + status
                    + "\" must be one of the workflow's statuses");
        }
    }

    private static Map<String, String> renames(List<String> current, List<NamedEntry> entries, String kind) {
        List<String> existing = current == null ? List.of() : current;
        Set<String> referenced = new LinkedHashSet<>();
        for (NamedEntry entry : entries) {
            if (entry.getOriginalName() != null) {
                referenced.add(entry.getOriginalName());
            }
        }
        Map<String, String> renames = new LinkedHashMap<>();
        Set<String> claimed = new LinkedHashSet<>();
        for (NamedEntry entry : entries) {
            String original = entry.getOriginalName();
            if (original == null || !existing.contains(original)) {
                continue;
            }
            if (!claimed.add(original)) {
                throw new IllegalArgumentException("The " + kind + " \"" + original + "\" is renamed twice");
            }
            String name = trim(entry.getName());
            if (!original.equals(name)) {
                // A rename onto a name this edit still references (kept or renamed elsewhere) would corrupt
                // data if applied alongside another rename in the same request (a swap or a chain): renames
                // run one after another, so "A"->"B" plus "B"->"A" would leave everything in "A", and
                // "A"->"B" plus "B"->"C" would carry "A" on to "C". Renaming onto a name the edit removes
                // entirely (not referenced by any entry) is fine — nothing is left there to collide with.
                if (referenced.contains(name)) {
                    throw new IllegalArgumentException("The " + kind + " \"" + original + "\" cannot be renamed to \""
                            + name + "\" while \"" + name + "\" is still in the list; rename one at a time");
                }
                renames.put(original, name);
            }
        }
        return renames;
    }

    private static Set<String> removed(List<String> current, List<NamedEntry> entries) {
        Set<String> referenced = new LinkedHashSet<>();
        for (NamedEntry entry : entries) {
            if (entry.getOriginalName() != null) {
                referenced.add(entry.getOriginalName());
            }
        }
        Set<String> removed = new LinkedHashSet<>();
        for (String name : current == null ? List.<String>of() : current) {
            if (!referenced.contains(name)) {
                removed.add(name);
            }
        }
        return removed;
    }

    private static Set<String> missing(List<String> current, List<String> submitted) {
        Set<String> kept = submitted == null ? Set.of() : new LinkedHashSet<>(submitted);
        Set<String> removed = new LinkedHashSet<>();
        for (String name : current == null ? List.<String>of() : current) {
            if (!kept.contains(name)) {
                removed.add(name);
            }
        }
        return removed;
    }

    private static Map<String, String> removedStages(List<RemediationStage> current, List<RemediationStage> submitted) {
        Set<String> keptIds = new LinkedHashSet<>();
        if (submitted != null) {
            submitted.stream().filter(Objects::nonNull).map(RemediationStage::getId)
                    .filter(Objects::nonNull).forEach(keptIds::add);
        }
        Map<String, String> removed = new LinkedHashMap<>();
        if (current != null) {
            for (RemediationStage stage : current) {
                if (stage != null && stage.getId() != null && !keptIds.contains(stage.getId())) {
                    removed.put(stage.getId(), stage.getName());
                }
            }
        }
        return removed;
    }

    /** The stages a save keeps: a missing or empty list is saved as the default stages, so it removes nothing they share. */
    private static List<RemediationStage> effectiveStages(List<RemediationStage> submitted) {
        return submitted == null || submitted.isEmpty() ? AssessmentWorkflow.defaultRemediationStages() : submitted;
    }

    private static List<String> trimmedNames(List<NamedEntry> entries) {
        List<String> names = new ArrayList<>();
        if (entries != null) {
            for (NamedEntry entry : entries) {
                names.add(trim(entry.getName()));
            }
        }
        return names;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
