package com.faction.clientportal.dto;

import com.faction.clientportal.model.AiProviderType;
import lombok.Data;

import java.util.List;

/**
 * Create/update request for an AI provider. On update, null fields mean "no change";
 * an apiKey equal to the masked sentinel is ignored so masked values round-trip safely.
 */
@Data
public class SaveAiProviderConfigRequest {
    private String name;
    private AiProviderType providerType;
    private String baseUrl;
    private String apiKey;
    private String apiVersion;
    private List<String> models;
    private String defaultModel;
    private Boolean enabled;
}
