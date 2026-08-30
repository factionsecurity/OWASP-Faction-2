package com.faction.clientportal.service;

import com.faction.clientportal.dto.AiProviderConfigDto;
import com.faction.clientportal.dto.SaveAiProviderConfigRequest;
import com.faction.clientportal.dto.TestAiProviderRequest;
import com.faction.clientportal.dto.TestAiProviderResponse;
import com.faction.clientportal.dto.TestAiProviderResponse.AiModelInfo;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.AiProviderConfig;
import com.faction.clientportal.model.AiProviderType;
import com.faction.clientportal.edition.EditionPolicy;
import com.faction.clientportal.edition.Quota;
import com.faction.clientportal.edition.QuotaUsageService;
import com.faction.clientportal.repository.AiProviderConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiProviderConfigService {

    private static final String MASKED = "••••••••";
    private static final String DEFAULT_AZURE_API_VERSION = "2024-10-21";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int TIMEOUT_MS = 10_000;

    private final AiProviderConfigRepository aiProviderConfigRepository;
    private final EditionPolicy editionPolicy;
    private final QuotaUsageService quotaUsageService;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    public List<AiProviderConfigDto> getProviders() {
        return aiProviderConfigRepository.findAll().stream()
                .sorted(Comparator.comparing(AiProviderConfig::getName, String.CASE_INSENSITIVE_ORDER))
                .map(AiProviderConfigDto::fromEntity)
                .toList();
    }

    public AiProviderConfigDto createProvider(SaveAiProviderConfigRequest request) {
        editionPolicy.requireHeadroom(Quota.AI_PROVIDERS, quotaUsageService.current(Quota.AI_PROVIDERS));

        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("Provider name is required");
        }
        if (request.getProviderType() == null) {
            throw new IllegalArgumentException("Provider type is required");
        }
        AiProviderConfig config = AiProviderConfig.builder()
                .createdAt(LocalDateTime.now())
                .build();
        applyRequest(config, request);
        validate(config);
        return AiProviderConfigDto.fromEntity(aiProviderConfigRepository.save(config));
    }

    public AiProviderConfigDto updateProvider(String id, SaveAiProviderConfigRequest request) {
        AiProviderConfig config = getEntity(id);
        applyRequest(config, request);
        validate(config);
        return AiProviderConfigDto.fromEntity(aiProviderConfigRepository.save(config));
    }

    public void deleteProvider(String id) {
        aiProviderConfigRepository.delete(getEntity(id));
    }

    private AiProviderConfig getEntity(String id) {
        return aiProviderConfigRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AI provider not found: " + id));
    }

    /** Null request fields mean "no change"; a masked API key preserves the stored one. */
    private void applyRequest(AiProviderConfig config, SaveAiProviderConfigRequest request) {
        if (request.getName() != null && !request.getName().isBlank()) config.setName(request.getName().trim());
        if (request.getProviderType() != null) config.setProviderType(request.getProviderType());
        if (request.getBaseUrl() != null) {
            config.setBaseUrl(request.getBaseUrl().isBlank() ? null : trimTrailingSlash(request.getBaseUrl().trim()));
        }
        if (request.getApiVersion() != null) {
            config.setApiVersion(request.getApiVersion().isBlank() ? null : request.getApiVersion().trim());
        }
        if (request.getModels() != null) {
            config.setModels(new ArrayList<>(request.getModels().stream()
                    .filter(m -> m != null && !m.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList()));
        }
        if (request.getDefaultModel() != null) {
            config.setDefaultModel(request.getDefaultModel().isBlank() ? null : request.getDefaultModel().trim());
        }
        if (request.getEnabled() != null) config.setEnabled(request.getEnabled());

        String key = request.getApiKey();
        if (key != null && !key.isBlank() && !key.equals(MASKED)) {
            config.setEncryptedApiKey(encryptionService.isConfigured() ? encryptionService.encrypt(key) : key);
        }
        config.setUpdatedAt(LocalDateTime.now());
    }

    private void validate(AiProviderConfig config) {
        if (config.getProviderType().requiresBaseUrl()
                && (config.getBaseUrl() == null || config.getBaseUrl().isBlank())) {
            throw new IllegalArgumentException(config.getProviderType() + " providers require a base URL");
        }
        if (config.getDefaultModel() != null && !config.getModels().contains(config.getDefaultModel())) {
            config.getModels().add(config.getDefaultModel());
        }
    }

    /**
     * Tests connectivity by listing the endpoint's models, which doubles as model
     * discovery for the UI. Never throws — failures come back as success=false.
     */
    public TestAiProviderResponse testProvider(TestAiProviderRequest request) {
        AiProviderType type = request.getProviderType();
        String baseUrl = request.getBaseUrl();
        String apiVersion = request.getApiVersion();
        String apiKey = request.getApiKey();

        // Fall back to the stored provider for anything the caller didn't supply
        if (request.getId() != null && !request.getId().isBlank()) {
            AiProviderConfig stored = getEntity(request.getId());
            if (type == null) type = stored.getProviderType();
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = stored.getBaseUrl();
            if (apiVersion == null || apiVersion.isBlank()) apiVersion = stored.getApiVersion();
            if ((apiKey == null || apiKey.isBlank() || apiKey.equals(MASKED))
                    && stored.getEncryptedApiKey() != null && !stored.getEncryptedApiKey().isBlank()) {
                try {
                    apiKey = getDecryptedApiKey(stored);
                } catch (Exception e) {
                    log.warn("Failed to decrypt stored API key for AI provider {}: {}", stored.getId(), e.getMessage());
                    return TestAiProviderResponse.builder()
                            .success(false)
                            .message("Stored API key could not be decrypted — re-enter it and try again.")
                            .details(e.getClass().getSimpleName())
                            .build();
                }
            }
        }

        if (type == null) {
            return TestAiProviderResponse.builder()
                    .success(false).message("Provider type is required.").build();
        }
        if (apiKey != null && apiKey.equals(MASKED)) {
            apiKey = null;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = type.getDefaultBaseUrl();
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return TestAiProviderResponse.builder()
                    .success(false).message("Base URL is required for " + type + " providers.").build();
        }
        baseUrl = trimTrailingSlash(baseUrl.trim());

        String url;
        HttpHeaders headers = new HttpHeaders();
        switch (type) {
            case ANTHROPIC -> {
                url = baseUrl + "/models";
                if (apiKey != null) headers.set("x-api-key", apiKey);
                headers.set("anthropic-version", ANTHROPIC_VERSION);
            }
            case AZURE_OPENAI -> {
                String version = (apiVersion == null || apiVersion.isBlank()) ? DEFAULT_AZURE_API_VERSION : apiVersion;
                url = baseUrl + "/openai/models?api-version=" + version;
                if (apiKey != null) headers.set("api-key", apiKey);
            }
            default -> { // OPENAI, OPENROUTER, OPENAI_COMPATIBLE
                url = baseUrl + "/models";
                if (apiKey != null) headers.setBearerAuth(apiKey);
            }
        }

        try {
            ResponseEntity<String> response = buildRestTemplate()
                    .exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            List<AiModelInfo> models = parseModels(response.getBody());
            return TestAiProviderResponse.builder()
                    .success(true)
                    .message("Connection successful. " + models.size() + " model(s) available.")
                    .models(models)
                    .build();
        } catch (HttpStatusCodeException e) {
            log.warn("AI provider test failed ({}): HTTP {}", type, e.getStatusCode().value());
            return TestAiProviderResponse.builder()
                    .success(false)
                    .message("Connection failed: HTTP " + e.getStatusCode().value()
                            + (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403
                                    ? " — check the API key." : ""))
                    .details(truncate(e.getResponseBodyAsString()))
                    .build();
        } catch (Exception e) {
            log.warn("AI provider test failed ({}): {}", type, e.getMessage());
            return TestAiProviderResponse.builder()
                    .success(false)
                    .message("Connection failed: " + e.getMessage())
                    .details(e.getClass().getSimpleName())
                    .build();
        }
    }

    /** Parses OpenAI-style {"data":[{"id":...}]} lists; Anthropic adds display_name. */
    private List<AiModelInfo> parseModels(String body) {
        List<AiModelInfo> models = new ArrayList<>();
        if (body == null || body.isBlank()) return models;
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.has("data") ? root.get("data") : root.get("models");
            if (data == null || !data.isArray()) return models;
            for (JsonNode node : data) {
                String id = node.path("id").asText(null);
                if (id == null || id.isBlank()) continue;
                String name = node.path("display_name").asText(null);
                if (name == null) name = node.path("name").asText(null);
                models.add(new AiModelInfo(id, name != null ? name : id));
            }
            models.sort(Comparator.comparing(AiModelInfo::getId, String.CASE_INSENSITIVE_ORDER));
        } catch (Exception e) {
            log.warn("Failed to parse model list from AI provider: {}", e.getMessage());
        }
        return models;
    }

    /** Decrypted API key for a stored provider — for internal use by AI features, never exposed via DTOs. */
    public String getDecryptedApiKey(AiProviderConfig config) {
        if (config.getEncryptedApiKey() == null || config.getEncryptedApiKey().isBlank()) return null;
        return encryptionService.isConfigured()
                ? encryptionService.decrypt(config.getEncryptedApiKey())
                : config.getEncryptedApiKey();
    }

    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}
