package com.faction.clientportal.dto.workflow;

import com.faction.clientportal.model.RemediationStage;
import com.faction.clientportal.model.VulnerabilitySla;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** One workflow for the API: its settings, the built-in vulnerability statuses every workflow shares, and renames still running. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowDto {

    private String id;
    private String name;
    private boolean defaultWorkflow;
    private boolean archived;
    private List<String> statuses;
    private String newAssessmentStatus;
    private String inProgressStatus;
    private String completedStatus;
    private Map<String, String> statusColors;
    private List<VulnerabilitySla> vulnerabilitySlas;
    private List<String> vulnerabilityStatuses;
    private List<String> builtInVulnerabilityStatuses;
    private List<RemediationStage> remediationStages;
    private boolean allowSelfPeerReview;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<RenameInProgress> renamesInProgress;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RenameInProgress {
        private String fromName;
        private String toName;
        private long processed;
    }
}
