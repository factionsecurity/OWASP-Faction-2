package com.faction.clientportal.edition;

import com.faction.clientportal.repository.AiProviderConfigRepository;
import com.faction.clientportal.repository.AiPromptTemplateRepository;
import com.faction.clientportal.repository.ExtensionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;

/**
 * Counts what currently occupies each {@link Quota}.
 *
 * <p>One place decides what "counts", so the number a service checks before a write and
 * the number the UI shows next to the cap can never disagree — a mismatch there reads to
 * the operator as an off-by-one bug in the licence.
 */
@Service
@RequiredArgsConstructor
public class QuotaUsageService {

    private final AiProviderConfigRepository aiProviderConfigRepository;
    private final AiPromptTemplateRepository aiPromptTemplateRepository;
    private final ExtensionRepository extensionRepository;

    public long current(Quota quota) {
        return switch (quota) {
            case AI_PROVIDERS -> aiProviderConfigRepository.count();
            case AI_PROMPTS   -> aiPromptTemplateRepository.count();
            case EXTENSIONS   -> extensionRepository.countByDeletedAtIsNull();
        };
    }

    public Map<Quota, Long> all() {
        Map<Quota, Long> usage = new EnumMap<>(Quota.class);
        for (Quota quota : Quota.values()) {
            usage.put(quota, current(quota));
        }
        return usage;
    }
}
