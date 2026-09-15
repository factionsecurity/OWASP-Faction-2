package com.faction.clientportal.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** What moving an assessment to another workflow changes (or changed, when {@code applied}). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowMovePreviewDto {

    private String assessmentId;
    private String fromWorkflowId;
    private String toWorkflowId;
    private String fromStatus;
    private String toStatus;
    private int findingCount;
    /** Finding statuses that change, grouped as from → to with how many findings. */
    private List<StatusChange> findingStatusChanges;
    /** Findings whose stored due or warning date changes under the target's SLAs. */
    private int dueDateChanges;
    private int remappedStageCompletions;
    /** Stage completions with no stage of the same name on the target; kept but not shown. */
    private int unmappedStageCompletions;
    private boolean applied;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusChange {
        private String from;
        private String to;
        private long count;
    }
}
