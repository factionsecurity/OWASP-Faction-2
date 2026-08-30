package com.faction.clientportal.service;

import com.faction.clientportal.model.AssessmentWorkflowConfig;
import com.faction.clientportal.model.AssessmentWorkflowConfig.RemediationStage;
import com.faction.clientportal.repository.AssessmentWorkflowConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AssessmentWorkflowConfigService {

    static final String SINGLETON_ID = "singleton";

    private final AssessmentWorkflowConfigRepository repository;

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
        return repository.save(config);
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

    /**
     * Returns true if the given status string represents a "completed" state.
     * Checks both the configured completedStatus and legacy enum string values for
     * backwards compatibility with data created before this feature was added.
     */
    public boolean isCompletedStatus(String status) {
        if (status == null) return false;
        String configured = getConfig().getCompletedStatus();
        return (configured != null && configured.equals(status))
                || "COMPLETED".equals(status)
                || "APPROVED".equals(status)
                || "ARCHIVED".equals(status);
    }
}
