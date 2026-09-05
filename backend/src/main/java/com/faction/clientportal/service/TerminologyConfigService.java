package com.faction.clientportal.service;

import com.faction.clientportal.dto.TerminologyConfigRequest;
import com.faction.clientportal.model.TerminologyConfig;
import com.faction.clientportal.model.VulnerabilitySeverity;
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

    /**
     * Applies a partial update onto the stored config. A field that is null or blank keeps the
     * value it already had — a blank label would leave a screen with a gap where a noun should be,
     * and an omitted one was never meant to change.
     */
    public TerminologyConfig updateConfig(TerminologyConfigRequest request) {
        TerminologyConfig current = getConfig();

        current.setOrganizationSingular(orDefault(request.getOrganizationSingular(),
                current.getOrganizationSingular()));
        current.setOrganizationPlural(orDefault(request.getOrganizationPlural(),
                current.getOrganizationPlural()));
        current.setSubOrganizationSingular(orDefault(request.getSubOrganizationSingular(),
                current.getSubOrganizationSingular()));
        current.setSubOrganizationPlural(orDefault(request.getSubOrganizationPlural(),
                current.getSubOrganizationPlural()));
        current.setSeverityCritical(orDefault(request.getSeverityCritical(),
                current.getSeverityCritical()));
        current.setSeverityHigh(orDefault(request.getSeverityHigh(), current.getSeverityHigh()));
        current.setSeverityMedium(orDefault(request.getSeverityMedium(), current.getSeverityMedium()));
        current.setSeverityLow(orDefault(request.getSeverityLow(), current.getSeverityLow()));
        current.setSeverityInformational(orDefault(request.getSeverityInformational(),
                current.getSeverityInformational()));

        TerminologyConfig saved = repository.save(current);
        log.info("Terminology updated: {} / {} / severities {}..{}", saved.getOrganizationPlural(),
                saved.getSubOrganizationPlural(), saved.getSeverityCritical(),
                saved.getSeverityInformational());
        return saved;
    }

    /**
     * This installation's word for one severity, for server-rendered output — reports and the
     * digest email. The interface has its own copy via the terminology endpoint.
     */
    public String severityLabel(VulnerabilitySeverity severity) {
        return getConfig().labelFor(severity);
    }

    /**
     * The same, for a rating held as free text — likelihood and impact. Passes through anything
     * that is not one of the five severities.
     */
    public String severityLabelForName(String name) {
        return getConfig().labelForName(name);
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
