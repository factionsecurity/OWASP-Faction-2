package com.faction.clientportal.service;

import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.repository.AssessmentWorkflowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Loads the {@link WorkflowCatalog}. Callers load once per request or job run; nothing is cached. */
@Service
@RequiredArgsConstructor
public class WorkflowCatalogService {

    private final AssessmentWorkflowRepository repository;
    private final AssessmentWorkflowConfigService workflowConfigService;

    /** Every workflow in one query, with Default Workflow created first if it is missing. */
    public WorkflowCatalog load() {
        List<AssessmentWorkflow> workflows = new ArrayList<>(repository.findAll());
        boolean hasDefaultFlagged = workflows.stream().anyMatch(AssessmentWorkflow::isDefaultWorkflow);
        boolean hasDefaultId = workflows.stream().anyMatch(w -> AssessmentWorkflow.DEFAULT_ID.equals(w.getId()));
        if (!hasDefaultFlagged && !hasDefaultId) {
            workflows.add(workflowConfigService.ensureDefaultWorkflow());
        }
        return WorkflowCatalog.of(workflows);
    }

    /** The workflow of one assessment (Default Workflow when it has none or an unknown one). */
    public AssessmentWorkflow forAssessment(Assessment assessment) {
        return load().forAssessment(assessment);
    }
}
