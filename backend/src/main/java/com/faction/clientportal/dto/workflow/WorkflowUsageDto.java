package com.faction.clientportal.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** How many assessment types and (non-deleted) assessments use one workflow, for the admin view. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowUsageDto {
    private String workflowId;
    private long assessmentTypeCount;
    private long assessmentCount;
}
