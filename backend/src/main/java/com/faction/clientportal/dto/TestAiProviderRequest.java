package com.faction.clientportal.dto;

import com.faction.clientportal.model.AiProviderType;
import lombok.Data;

/**
 * Connection test / model discovery request. When apiKey is blank or masked and
 * {@code id} references a saved provider, the stored key is used instead.
 */
@Data
public class TestAiProviderRequest {
    private String id;
    private AiProviderType providerType;
    private String baseUrl;
    private String apiKey;
    private String apiVersion;
}
