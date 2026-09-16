package com.faction.clientportal.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Move an assessment to another workflow, or with {@code dryRun} only preview what the move would change. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MoveWorkflowRequest {
    @NotBlank
    private String workflowId;
    private boolean dryRun;
}
