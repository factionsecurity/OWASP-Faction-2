package com.faction.clientportal.service;

import com.faction.clientportal.dto.UpdateWebSearchConfigRequest;
import com.faction.clientportal.dto.WebSearchConfigDto;
import com.faction.clientportal.model.WebSearchConfig;
import com.faction.clientportal.repository.WebSearchConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebSearchConfigService {

    private static final String MASKED = "••••••••";

    private final WebSearchConfigRepository webSearchConfigRepository;
    private final EncryptionService encryptionService;

    public WebSearchConfig getOrCreate() {
        return webSearchConfigRepository.findById(WebSearchConfig.SINGLETON_ID)
                .orElseGet(() -> webSearchConfigRepository.save(
                        WebSearchConfig.builder().id(WebSearchConfig.SINGLETON_ID).build()));
    }

    public WebSearchConfigDto getConfig() {
        return WebSearchConfigDto.fromEntity(getOrCreate());
    }

    public WebSearchConfigDto updateConfig(UpdateWebSearchConfigRequest request) {
        WebSearchConfig config = getOrCreate();
        if (request.getEnabled() != null) config.setEnabled(request.getEnabled());
        if (request.getAllowInAskAi() != null) config.setAllowInAskAi(request.getAllowInAskAi());
        if (request.getProvider() != null) config.setProvider(request.getProvider());

        String key = request.getApiKey();
        if (key != null && !key.isBlank() && !key.equals(MASKED)) {
            config.setEncryptedApiKey(encryptionService.isConfigured()
                    ? encryptionService.encrypt(key) : key);
        }
        webSearchConfigRepository.save(config);
        return WebSearchConfigDto.fromEntity(config);
    }

    /** Decrypted key for internal AI-tool use — never exposed via DTOs. */
    public String getDecryptedApiKey(WebSearchConfig config) {
        if (config.getEncryptedApiKey() == null || config.getEncryptedApiKey().isBlank()) return null;
        return encryptionService.isConfigured()
                ? encryptionService.decrypt(config.getEncryptedApiKey())
                : config.getEncryptedApiKey();
    }
}
