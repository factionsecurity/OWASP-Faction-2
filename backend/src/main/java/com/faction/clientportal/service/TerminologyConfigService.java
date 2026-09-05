package com.faction.clientportal.service;

import com.faction.clientportal.model.TerminologyConfig;
import com.faction.clientportal.repository.TerminologyConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TerminologyConfigService {

    static final String SINGLETON_ID = "singleton";

    private final TerminologyConfigRepository repository;

    public TerminologyConfig getConfig() {
        return repository.findById(SINGLETON_ID)
                .orElseGet(() -> repository.save(TerminologyConfig.builder().id(SINGLETON_ID).build()));
    }

    public TerminologyConfig updateConfig(TerminologyConfig config) {
        config.setId(SINGLETON_ID);
        TerminologyConfig current = getConfig();

        // A blank label leaves a screen with a gap where a noun should be, so an empty value means
        // "leave this one alone" rather than "erase it".
        config.setOrganizationSingular(orDefault(config.getOrganizationSingular(),
                current.getOrganizationSingular()));
        config.setOrganizationPlural(orDefault(config.getOrganizationPlural(),
                current.getOrganizationPlural()));
        config.setSubOrganizationSingular(orDefault(config.getSubOrganizationSingular(),
                current.getSubOrganizationSingular()));
        config.setSubOrganizationPlural(orDefault(config.getSubOrganizationPlural(),
                current.getSubOrganizationPlural()));

        TerminologyConfig saved = repository.save(config);
        log.info("Terminology updated: {} / {}", saved.getOrganizationPlural(),
                saved.getSubOrganizationPlural());
        return saved;
    }

    private static String orDefault(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        // Trimmed, because a trailing space is invisible in the settings field and glaring in a
        // heading built by concatenation.
        String trimmed = value.trim();
        if (trimmed.length() > 100) {
            throw new IllegalArgumentException("A label cannot be longer than 100 characters");
        }
        return trimmed;
    }
}
