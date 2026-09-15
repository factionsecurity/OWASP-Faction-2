package com.faction.clientportal.dto.workflow;

import com.faction.clientportal.model.RemediationStage;
import com.faction.clientportal.model.VulnerabilitySla;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * An edit to one workflow's settings. Status and vulnerability status rows carry the name they had
 * when the editor loaded ({@code originalName}, null for a new row), so renaming a row is a rename
 * rather than a removal plus an addition. Role statuses and colours use the new names.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateWorkflowRequest {

    /** The workflow's new name; null keeps the current one. */
    private String name;
    private List<NamedEntry> statuses;
    private String newAssessmentStatus;
    private String inProgressStatus;
    private String completedStatus;
    private Map<String, String> statusColors;
    private List<VulnerabilitySla> vulnerabilitySlas;
    /** The workflow's additional vulnerability statuses; built-in ones are implicit and never listed. */
    private List<NamedEntry> vulnerabilityStatuses;
    private List<RemediationStage> remediationStages;
    private boolean allowSelfPeerReview;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NamedEntry {
        /** The name when the editor loaded; null for a row added in this edit. */
        private String originalName;
        private String name;
    }
}
