package com.faction.clientportal.dto;

import lombok.Data;

/** Derives a vulnerability title from its description/details text. */
@Data
public class SuggestAiTitleRequest {
    private String assessmentId;
    /** Set when editing an existing vulnerability */
    private String vulnerabilityId;
    /** Current (possibly unsaved) editor content, HTML */
    private String description;
    private String details;
}
