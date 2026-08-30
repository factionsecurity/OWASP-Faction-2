package com.faction.clientportal.dto;

import com.faction.clientportal.model.WebSearchConfig;
import com.faction.clientportal.model.WebSearchProviderType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WebSearchConfigDto {

    private static final String MASKED = "••••••••";

    private boolean enabled;
    private boolean allowInAskAi;
    private WebSearchProviderType provider;
    private String apiKey; // null when not set, MASKED when an encrypted value is stored

    public static WebSearchConfigDto fromEntity(WebSearchConfig config) {
        return WebSearchConfigDto.builder()
                .enabled(config.isEnabled())
                .allowInAskAi(config.isAllowInAskAi())
                .provider(config.getProvider())
                .apiKey(config.getEncryptedApiKey() != null && !config.getEncryptedApiKey().isBlank()
                        ? MASKED : null)
                .build();
    }
}
