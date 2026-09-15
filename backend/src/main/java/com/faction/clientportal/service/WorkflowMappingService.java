package com.faction.clientportal.service;

import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.Vulnerability;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The one place a finding is mapped onto another workflow: carrying a finding into an assessment on
 * another workflow now, moving an assessment between workflows later. Does not save.
 */
@Service
@RequiredArgsConstructor
public class WorkflowMappingService {

    /** Where a status the target workflow doesn't know goes. */
    static final String UNMAPPED_STATUS = "Open";

    private final SlaService slaService;

    /**
     * Keeps a built-in status and any status the target workflow has; any other status becomes Open.
     * Then recalculates the stored SLA dates under the target from the finding's original opened date.
     */
    public void mapFinding(Vulnerability finding, AssessmentWorkflow target) {
        String status = finding.getStatus();
        if (status != null && !AssessmentWorkflows.isBuiltInVulnerabilityStatus(status)) {
            List<String> targetStatuses = target.getVulnerabilityStatuses();
            if (targetStatuses == null || !targetStatuses.contains(status)) {
                finding.setStatus(UNMAPPED_STATUS);
            }
        }
        slaService.refresh(finding, target);
    }
}
