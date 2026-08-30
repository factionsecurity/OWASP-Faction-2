package com.faction.clientportal.service;

import com.faction.clientportal.dto.ApplicationIdConfigDto;
import com.faction.clientportal.dto.ApplicationIdConfigUpdateRequest;
import com.faction.clientportal.model.ApplicationIdConfig;
import com.faction.clientportal.repository.ApplicationIdConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ApplicationIdConfigService {

    private static final String CONFIG_ID = "default";

    private final ApplicationIdConfigRepository repository;

    public ApplicationIdConfigDto getConfig() {
        ApplicationIdConfig config = getOrCreate();
        return toDto(config);
    }

    @Transactional
    public ApplicationIdConfigDto updateConfig(ApplicationIdConfigUpdateRequest request) {
        ApplicationIdConfig config = getOrCreate();

        if (request.getPrefix() != null) {
            config.setPrefix(request.getPrefix());
        }
        if (request.getNextNumber() != null) {
            config.setNextNumber(request.getNextNumber());
        }
        if (request.getEnabled() != null) {
            config.setEnabled(request.getEnabled());
        }
        config.setLastUpdatedBy("admin");
        config.setUpdatedAt(LocalDateTime.now());

        ApplicationIdConfig saved = repository.save(config);
        return toDto(saved);
    }

    @Transactional
    public String generateNextAppId() {
        ApplicationIdConfig config = repository.findByIdForUpdate(CONFIG_ID)
                .orElseGet(() -> {
                    getOrCreate();
                    return repository.findByIdForUpdate(CONFIG_ID)
                            .orElseThrow(() -> new IllegalStateException("ApplicationIdConfig row missing"));
                });

        String appId = config.getPrefix() + "-" + config.getNextNumber();
        config.setNextNumber(config.getNextNumber() + 1);
        repository.save(config);
        return appId;
    }

    public boolean isEnabled() {
        ApplicationIdConfig config = getOrCreate();
        return Boolean.TRUE.equals(config.getEnabled());
    }

    public List<String> getPreviewNext(int count) {
        ApplicationIdConfig config = getOrCreate();
        List<String> preview = new ArrayList<>();
        int next = config.getNextNumber();
        for (int i = 0; i < count; i++) {
            preview.add(config.getPrefix() + "-" + (next + i));
        }
        return preview;
    }

    private ApplicationIdConfig getOrCreate() {
        return repository.findById(CONFIG_ID).orElseGet(() -> {
            ApplicationIdConfig config = ApplicationIdConfig.builder()
                    .id(CONFIG_ID)
                    .prefix("ASMT")
                    .nextNumber(1)
                    .padding(0)
                    .enabled(true)
                    .createdBy("system")
                    .lastUpdatedBy("system")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            return repository.save(config);
        });
    }

    private ApplicationIdConfigDto toDto(ApplicationIdConfig config) {
        return ApplicationIdConfigDto.builder()
                .id(config.getId())
                .prefix(config.getPrefix())
                .nextNumber(config.getNextNumber())
                .padding(config.getPadding())
                .enabled(config.getEnabled())
                .createdBy(config.getCreatedBy())
                .lastUpdatedBy(config.getLastUpdatedBy())
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .build();
    }
}
