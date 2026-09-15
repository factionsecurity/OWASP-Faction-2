package com.faction.clientportal.exception;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A workflow change refused because something still depends on what it would change: a status an
 * assessment is in, a stage with completions, a name another workflow has. Answered 409 with every
 * violation and its count, so the editor can show exactly why.
 */
public class WorkflowConflictException extends BusinessRuleException {

    public static final String ASSESSMENT_STATUS_IN_USE = "ASSESSMENT_STATUS_IN_USE";
    public static final String VULNERABILITY_STATUS_IN_USE = "VULNERABILITY_STATUS_IN_USE";
    public static final String REMEDIATION_STAGE_IN_USE = "REMEDIATION_STAGE_IN_USE";
    public static final String RENAME_IN_PROGRESS = "RENAME_IN_PROGRESS";
    public static final String NAME_TAKEN = "NAME_TAKEN";
    public static final String DEFAULT_WORKFLOW = "DEFAULT_WORKFLOW";
    public static final String USED_BY_ASSESSMENT_TYPES = "USED_BY_ASSESSMENT_TYPES";
    public static final String USED_BY_ASSESSMENTS = "USED_BY_ASSESSMENTS";
    public static final String TARGET_ARCHIVED = "TARGET_ARCHIVED";

    /** One reason for the refusal: what kind, the name it concerns, and how many things use it (0 when not a count). */
    public record Violation(String kind, String name, long count) {

        String describe() {
            return switch (kind) {
                case ASSESSMENT_STATUS_IN_USE -> "Status \"" + name + "\" is used by " + count + " assessment(s)";
                case VULNERABILITY_STATUS_IN_USE -> "Vulnerability status \"" + name + "\" is used by " + count + " finding(s)";
                case REMEDIATION_STAGE_IN_USE -> "Remediation stage \"" + name + "\" has " + count + " completion(s)";
                case RENAME_IN_PROGRESS -> "Vulnerability status \"" + name + "\" is still being renamed";
                case NAME_TAKEN -> "Another workflow is already named \"" + name + "\"";
                case DEFAULT_WORKFLOW -> "\"" + name + "\" is the default workflow and cannot be archived or deleted";
                case USED_BY_ASSESSMENT_TYPES -> "\"" + name + "\" is used by " + count + " assessment type(s)";
                case USED_BY_ASSESSMENTS -> "\"" + name + "\" is used by " + count + " assessment(s)";
                case TARGET_ARCHIVED -> "\"" + name + "\" is archived";
                default -> kind + ": " + name;
            };
        }
    }

    private final transient List<Violation> violations;

    public WorkflowConflictException(List<Violation> violations) {
        super(violations.stream().map(Violation::describe).collect(Collectors.joining("; ")));
        this.violations = List.copyOf(violations);
    }

    public WorkflowConflictException(Violation violation) {
        this(List.of(violation));
    }

    public List<Violation> getViolations() {
        return violations;
    }
}
