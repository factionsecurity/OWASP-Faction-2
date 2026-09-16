package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.dto.workflow.CreateWorkflowRequest;
import com.faction.clientportal.dto.workflow.UpdateWorkflowRequest;
import com.faction.clientportal.dto.workflow.WorkflowDto;
import com.faction.clientportal.dto.workflow.WorkflowUsageDto;
import com.faction.clientportal.edition.Feature;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.AuthenticatedOnly;
import com.faction.clientportal.security.RequiresFeature;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.service.WorkflowAdminService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Assessment workflows: read by any signed-in user; managed with {@code config:write}; created only where Custom Workflows is available. */
@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
@Tag(name = "Workflows", description = "Assessment workflow management: listing, usage, copy-create, edit, archive and delete")
@SecurityRequirement(name = "bearerAuth")
public class WorkflowController {

    private final WorkflowAdminService workflowAdminService;

    @GetMapping
    @AuthenticatedOnly
    @Operation(summary = "List workflows", description = "Default Workflow first, then the others by name; archived ones only when asked.")
    public ResponseEntity<JsonApiResponse<List<WorkflowDto>>> list(
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return ResponseUtil.success("Workflows retrieved successfully", workflowAdminService.list(includeArchived));
    }

    @GetMapping("/usage")
    @RequiresPermission(Permission.CONFIG_WRITE)
    @Operation(summary = "Workflow usage counts", description = "Every workflow's assessment type and assessment counts, archived workflows included.")
    public ResponseEntity<JsonApiResponse<List<WorkflowUsageDto>>> usage() {
        return ResponseUtil.success("Workflow usage retrieved successfully", workflowAdminService.usage());
    }

    @GetMapping("/{id}")
    @AuthenticatedOnly
    @Operation(summary = "Get a workflow")
    public ResponseEntity<JsonApiResponse<WorkflowDto>> get(@PathVariable String id) {
        return ResponseUtil.success("Workflow retrieved successfully", workflowAdminService.get(id));
    }

    @PostMapping
    @RequiresFeature(Feature.CUSTOM_WORKFLOWS)
    @RequiresPermission(Permission.CONFIG_WRITE)
    @Operation(summary = "Create a workflow as a copy",
            description = "Creates a new workflow copied from an existing one, with fresh stage ids that inherit the source stages' email settings.")
    public ResponseEntity<JsonApiResponse<WorkflowDto>> create(@Valid @RequestBody CreateWorkflowRequest request) {
        return ResponseUtil.created("Workflow created successfully", workflowAdminService.create(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.CONFIG_WRITE)
    @Operation(summary = "Update a workflow")
    public ResponseEntity<JsonApiResponse<WorkflowDto>> update(@PathVariable String id,
                                                               @RequestBody UpdateWorkflowRequest request) {
        return ResponseUtil.success("Workflow updated successfully", workflowAdminService.update(id, request));
    }

    @PostMapping("/{id}/archive")
    @RequiresPermission(Permission.CONFIG_WRITE)
    @Operation(summary = "Archive a workflow", description = "Hides a workflow from pickers; its assessments keep working. Refused for Default Workflow or while an assessment type uses it.")
    public ResponseEntity<JsonApiResponse<WorkflowDto>> archive(@PathVariable String id) {
        return ResponseUtil.success("Workflow archived successfully", workflowAdminService.archive(id));
    }

    @PostMapping("/{id}/unarchive")
    @RequiresPermission(Permission.CONFIG_WRITE)
    @Operation(summary = "Unarchive a workflow")
    public ResponseEntity<JsonApiResponse<WorkflowDto>> unarchive(@PathVariable String id) {
        return ResponseUtil.success("Workflow unarchived successfully", workflowAdminService.unarchive(id));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.CONFIG_WRITE)
    @Operation(summary = "Delete a workflow", description = "Deletes an unused workflow, with all of its rename records and its stages' email settings.")
    public ResponseEntity<JsonApiResponse<Void>> delete(@PathVariable String id) {
        workflowAdminService.delete(id);
        return ResponseUtil.success("Workflow deleted successfully");
    }
}
