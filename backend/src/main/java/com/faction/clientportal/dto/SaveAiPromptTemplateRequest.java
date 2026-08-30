package com.faction.clientportal.dto;

import com.faction.clientportal.model.AiPromptScope;
import lombok.Data;

/** Create/update request for an AI prompt template. On update, null fields mean "no change". */
@Data
public class SaveAiPromptTemplateRequest {
    private String name;
    private String description;
    private AiPromptScope scope;
    private String prompt;
    /** Empty string clears the pinned provider (falls back to the default provider) */
    private String providerId;
    /** Empty string clears the pinned model (falls back to the provider's default) */
    private String model;
    private Boolean allowWebAccess;
    private Boolean enabled;
}
