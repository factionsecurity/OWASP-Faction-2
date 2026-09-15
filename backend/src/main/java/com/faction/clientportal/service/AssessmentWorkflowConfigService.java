package com.faction.clientportal.service;

import com.faction.clientportal.model.AssessmentWorkflowConfig;
import com.faction.clientportal.model.AssessmentWorkflowConfig.RemediationStage;
import com.faction.clientportal.model.AssessmentWorkflowConfig.VulnerabilitySla;
import com.faction.clientportal.repository.AssessmentWorkflowConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AssessmentWorkflowConfigService {

    static final String SINGLETON_ID = "singleton";

    private final AssessmentWorkflowConfigRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    /** Returns the config, creating it with defaults on first access. */
    public AssessmentWorkflowConfig getConfig() {
        return repository.findById(SINGLETON_ID).orElseGet(() -> {
            AssessmentWorkflowConfig defaults = AssessmentWorkflowConfig.builder()
                    .id(SINGLETON_ID)
                    .build();
            return repository.save(defaults);
        });
    }

    public AssessmentWorkflowConfig updateConfig(AssessmentWorkflowConfig config) {
        config.setId(SINGLETON_ID);
        config.setRemediationStages(normalizeStages(config.getRemediationStages()));
        // With no row yet, getConfig() would have seeded the defaults, so those are what changed from.
        List<VulnerabilitySla> previousSlas = repository.findById(SINGLETON_ID)
                .map(AssessmentWorkflowConfig::getVulnerabilitySlas)
                .orElseGet(AssessmentWorkflowConfig::defaultVulnerabilitySlas);
        AssessmentWorkflowConfig saved = repository.save(config);
        if (!normalizedSlas(previousSlas).equals(normalizedSlas(saved.getVulnerabilitySlas()))) {
            eventPublisher.publishEvent(new SlaConfigChangedEvent());
        }
        return saved;
    }

    /**
     * The SLAs as SlaService reads them — severity trimmed and upper-cased, with its day counts — as
     * an order-insensitive set, so reordering rows or retyping a severity's case is not a change.
     */
    static Set<String> normalizedSlas(List<VulnerabilitySla> slas) {
        if (slas == null) {
            return Set.of();
        }
        return slas.stream()
                .filter(s -> s != null && s.getSeverity() != null && !s.getSeverity().isBlank())
                .map(s -> s.getSeverity().trim().toUpperCase(Locale.ROOT)
                        + ":" + s.getPastDueDays() + ":" + s.getWarningDays())
                .collect(Collectors.toSet());
    }

    /**
     * The configured remediation stages, never empty: there must always be a terminal (last)
     * stage for closing a vulnerability, so a null/empty list falls back to the defaults.
     */
    public List<RemediationStage> remediationStages() {
        List<RemediationStage> stages = getConfig().getRemediationStages();
        return stages == null || stages.isEmpty()
                ? AssessmentWorkflowConfig.defaultRemediationStages() : stages;
    }

    /**
     * Stage ids are assigned server-side and are permanent — completions are keyed by them, so a
     * rename must never change the id. Blank-named stages are dropped; an empty submission falls
     * back to the defaults rather than leaving the config without a terminal stage.
     */
    private static List<RemediationStage> normalizeStages(List<RemediationStage> stages) {
        if (stages == null) {
            return AssessmentWorkflowConfig.defaultRemediationStages();
        }
        List<RemediationStage> normalized = new ArrayList<>();
        for (RemediationStage stage : stages) {
            if (stage == null || stage.getName() == null || stage.getName().isBlank()) continue;
            String id = stage.getId() == null || stage.getId().isBlank()
                    ? UUID.randomUUID().toString() : stage.getId();
            normalized.add(new RemediationStage(id, stage.getName().trim()));
        }
        return normalized.isEmpty() ? AssessmentWorkflowConfig.defaultRemediationStages() : normalized;
    }

    /** Whether the given status is the workflow's configured completed status. */
    public boolean isCompletedStatus(String status) {
        if (status == null) return false;
        return status.equals(getConfig().getCompletedStatus());
    }
}
