package com.faction.clientportal.dto;

import com.faction.clientportal.model.AiProviderConfig;
import com.faction.clientportal.model.AiProviderType;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AiProviderConfigDto {

    private static final String MASKED = "••••••••";

    private String id;
    private String name;
    private AiProviderType providerType;
    private String baseUrl;
    private String apiKey; // null when not set, MASKED when an encrypted value is stored
    private String apiVersion;
    private List<String> models;
    private String defaultModel;
    private boolean enabled;

    public static AiProviderConfigDto fromEntity(AiProviderConfig config) {
        return AiProviderConfigDto.builder()
                .id(config.getId())
                .name(config.getName())
                .providerType(config.getProviderType())
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getEncryptedApiKey() != null && !config.getEncryptedApiKey().isBlank()
                        ? MASKED : null)
                .apiVersion(config.getApiVersion())
                .models(config.getModels())
                .defaultModel(config.getDefaultModel())
                .enabled(config.isEnabled())
                .build();
    }
}
