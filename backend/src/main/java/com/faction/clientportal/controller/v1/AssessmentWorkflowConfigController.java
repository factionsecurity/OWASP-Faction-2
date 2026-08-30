package com.faction.clientportal.controller.v1;

import com.faction.clientportal.security.AuthenticatedOnly;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.model.AssessmentWorkflowConfig;
import com.faction.clientportal.service.AssessmentWorkflowConfigService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/config/assessment-workflow")
@RequiredArgsConstructor
@Tag(name = "Assessment Workflow Config", description = "Manage customizable assessment workflow statuses")
@SecurityRequirement(name = "bearerAuth")
public class AssessmentWorkflowConfigController {

    private final AssessmentWorkflowConfigService service;

    @GetMapping
    @AuthenticatedOnly
    @Operation(summary = "Get assessment workflow configuration",
               description = "Retrieve the configured assessment workflow statuses and settings.")
    public ResponseEntity<JsonApiResponse<AssessmentWorkflowConfig>> getConfig() {
        return ResponseUtil.success("Config retrieved successfully", service.getConfig());
    }

    @PutMapping
    @RequiresPermission(Permission.CONFIG_WRITE)
    @Operation(summary = "Update assessment workflow configuration",
               description = "Replace the assessment workflow statuses and settings.")
    public ResponseEntity<JsonApiResponse<AssessmentWorkflowConfig>> updateConfig(
            @RequestBody AssessmentWorkflowConfig config) {
        return ResponseUtil.success("Config updated successfully", service.updateConfig(config));
    }
}
