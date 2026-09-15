package com.faction.clientportal.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A new workflow, created as a copy of an existing one. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateWorkflowRequest {
    @NotBlank
    private String sourceWorkflowId;
    @NotBlank
    private String name;
}
