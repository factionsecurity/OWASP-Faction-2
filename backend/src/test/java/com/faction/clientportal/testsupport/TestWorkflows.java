package com.faction.clientportal.testsupport;

import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.RemediationStage;
import com.faction.clientportal.model.VulnerabilitySla;
import com.faction.clientportal.repository.AssessmentWorkflowRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A second workflow for tests, saved straight through the repository. It differs from Default
 * Workflow in every setting and shares no status or stage with it, so a behaviour that reads the
 * wrong workflow gives a visibly wrong result. Phase 2 onwards run their behaviour tests against both.
 */
public final class TestWorkflows {

    public static final String SECOND_ID = "second-workflow";
    public static final String SECOND_NAME = "Second Workflow";

    private TestWorkflows() {
    }

    /** A new, unsaved copy each call, with mutable collections. */
    public static AssessmentWorkflow secondWorkflow() {
        LocalDateTime created = LocalDateTime.of(2026, 9, 14, 0, 0);
        return AssessmentWorkflow.builder()
                .id(SECOND_ID)
                .name(SECOND_NAME)
                .defaultWorkflow(false)
                .archived(false)
                .statuses(new ArrayList<>(List.of("Draft", "Scoping", "Fieldwork", "Signed Off")))
                .newAssessmentStatus("Draft")
                .inProgressStatus("Fieldwork")
                .completedStatus("Signed Off")
                .statusColors(new HashMap<>(Map.of("Draft", "#6b7280", "Signed Off", "#16a34a")))
                .vulnerabilitySlas(new ArrayList<>(List.of(
                        new VulnerabilitySla("CRITICAL", 7, 3),
                        new VulnerabilitySla("HIGH", 14, 5),
                        new VulnerabilitySla("LOW", 90, 30))))
                .vulnerabilityStatuses(new ArrayList<>(List.of("Risk Accepted")))
                .remediationStages(new ArrayList<>(List.of(
                        new RemediationStage("second-qa", "QA"),
                        new RemediationStage("second-live", "Live"))))
                .allowSelfPeerReview(true)
                .createdAt(created)
                .updatedAt(created)
                .build();
    }

    public static AssessmentWorkflow saveSecondWorkflow(AssessmentWorkflowRepository repository) {
        return repository.save(secondWorkflow());
    }
}
