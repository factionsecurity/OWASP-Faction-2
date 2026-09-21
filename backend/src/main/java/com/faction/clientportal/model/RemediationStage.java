package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One remediation stage of a workflow. The last stage of a workflow is terminal. Stored as JSON
 * inside the workflow row.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RemediationStage {
    /**
     * Stable identifier completions are keyed by. Never changes once assigned, so renaming
     * a stage re-labels its historical completions instead of orphaning them.
     */
    private String id;
    /** Display name, free text (e.g. "QA", "UAT"). */
    private String name;
}
