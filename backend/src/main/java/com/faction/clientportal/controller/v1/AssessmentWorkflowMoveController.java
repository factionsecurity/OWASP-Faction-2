package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.dto.workflow.MoveWorkflowRequest;
import com.faction.clientportal.dto.workflow.WorkflowMovePreviewDto;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.service.AssessmentWorkflowMoveService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** "Move to workflow…" on an assessment: an admin action, previewed with {@code dryRun} before it is applied. */
@RestController
@RequestMapping("/api/v1/assessments")
@RequiredArgsConstructor
@Tag(name = "Assessments", description = "Assessment management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class AssessmentWorkflowMoveController {

    private final AssessmentWorkflowMoveService moveService;

    @PostMapping("/{id}/move-workflow")
    @RequiresPermission(Permission.CONFIG_WRITE)
    @Operation(summary = "Move an assessment to another workflow",
            description = "Maps the assessment's status and each finding through the target workflow, and remaps stage completions by stage name. Pass dryRun to preview without writing anything.")
    public ResponseEntity<JsonApiResponse<WorkflowMovePreviewDto>> move(@PathVariable String id,
                                                                       @Valid @RequestBody MoveWorkflowRequest request) {
        WorkflowMovePreviewDto result = moveService.move(id, request.getWorkflowId(), request.isDryRun());
        return ResponseUtil.success(request.isDryRun() ? "Move previewed" : "Assessment moved", result);
    }
}
