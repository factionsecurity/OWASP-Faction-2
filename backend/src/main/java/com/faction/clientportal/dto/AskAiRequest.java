package com.faction.clientportal.dto;

import lombok.Data;

/** Freeform "Ask AI" request from a rich text editor. */
@Data
public class AskAiRequest {
    private String assessmentId;
    /** Set when the editor belongs to a specific vulnerability */
    private String vulnerabilityId;
    /** The user's instruction, e.g. "rewrite this in simpler language" */
    private String question;
    /** Current editor content (HTML), passed to the model as context */
    private String currentText;
}
