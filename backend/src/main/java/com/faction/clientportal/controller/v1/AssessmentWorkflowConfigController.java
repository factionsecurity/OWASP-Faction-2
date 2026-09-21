package com.faction.clientportal.controller.v1;

import com.faction.clientportal.security.AuthenticatedOnly;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.service.AssessmentWorkflowConfigService;
import com.faction.clientportal.service.SlaRecalculationService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/config/assessment-workflow")
@RequiredArgsConstructor
@Tag(name = "Assessment Workflow Config", description = "Manage customizable assessment workflow statuses")
@SecurityRequirement(name = "bearerAuth")
public class AssessmentWorkflowConfigController {

    private final AssessmentWorkflowConfigService service;
    private final SlaRecalculationService slaRecalculationService;

    @GetMapping
    @AuthenticatedOnly
    @Operation(summary = "Get assessment workflow configuration",
               description = "Retrieve Default Workflow's statuses and settings. Kept for one release as the "
                       + "alias for Default Workflow.")
    public ResponseEntity<JsonApiResponse<AssessmentWorkflow>> getConfig() {
        return ResponseUtil.success("Config retrieved successfully", service.getConfig());
    }

    @PutMapping
    @RequiresPermission(Permission.CONFIG_WRITE)
    @Operation(summary = "Update assessment workflow configuration",
               description = "Replace Default Workflow's statuses and settings. Its id, name and default and "
                       + "archived flags are not changed. Kept for one release as the alias for Default Workflow.")
    public ResponseEntity<JsonApiResponse<AssessmentWorkflow>> updateConfig(
            @RequestBody AssessmentWorkflow config) {
        return ResponseUtil.success("Config updated successfully", service.updateConfig(config));
    }

    @PostMapping("/recalculate-sla")
    @RequiresPermission(Permission.CONFIG_WRITE)
    @Operation(summary = "Recalculate stored SLA due dates",
               description = "Recalculate every open finding's stored due and warning dates from the current "
                       + "SLAs in the background, and return findings no longer past due to Open. Use it to "
                       + "repair due dates after a recalculation failed.")
    public ResponseEntity<JsonApiResponse<Void>> recalculateSla() {
        slaRecalculationService.recalculateInBackground();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(JsonApiResponse.success("SLA recalculation started"));
    }
}
