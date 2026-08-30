package com.faction.clientportal.service;

import com.faction.clientportal.dto.AiAnonymizationConfigDto;
import com.faction.clientportal.dto.UpdateAiAnonymizationConfigRequest;
import com.faction.clientportal.model.AiAnonymizationConfig;
import com.faction.clientportal.repository.AiAnonymizationConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiAnonymizationConfigService {

    private final AiAnonymizationConfigRepository repository;

    public AiAnonymizationConfig getOrCreate() {
        return repository.findById(AiAnonymizationConfig.SINGLETON_ID)
                .orElseGet(() -> repository.save(
                        AiAnonymizationConfig.builder().id(AiAnonymizationConfig.SINGLETON_ID).build()));
    }

    public AiAnonymizationConfigDto getConfig() {
        return AiAnonymizationConfigDto.fromEntity(getOrCreate());
    }

    public AiAnonymizationConfigDto updateConfig(UpdateAiAnonymizationConfigRequest request) {
        AiAnonymizationConfig config = getOrCreate();
        if (request.getEnabled() != null) config.setEnabled(request.getEnabled());
        if (request.getPresidioUrl() != null) {
            String url = request.getPresidioUrl().isBlank() ? null : request.getPresidioUrl().trim();
            config.setPresidioUrl(url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
        }
        if (request.getScoreThreshold() != null) {
            double t = request.getScoreThreshold();
            config.setScoreThreshold(Math.max(0.0, Math.min(1.0, t)));
        }
        repository.save(config);
        return AiAnonymizationConfigDto.fromEntity(config);
    }
}
