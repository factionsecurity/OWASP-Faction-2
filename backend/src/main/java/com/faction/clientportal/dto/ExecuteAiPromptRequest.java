package com.faction.clientportal.dto;

import lombok.Data;

/** Runs an admin-defined prompt template against the current assessment. */
@Data
public class ExecuteAiPromptRequest {
    private String promptId;
    private String assessmentId;
    /** Set when the editor belongs to a specific vulnerability */
    private String vulnerabilityId;
    /** Current editor content (HTML), passed to the model as context */
    private String currentText;
}
