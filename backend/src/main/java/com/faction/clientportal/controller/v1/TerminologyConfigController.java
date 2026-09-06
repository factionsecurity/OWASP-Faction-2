package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.TerminologyConfigRequest;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.model.TerminologyConfig;
import com.faction.clientportal.security.AuthenticatedOnly;
import com.faction.clientportal.service.TerminologyConfigService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/config/terminology")
@RequiredArgsConstructor
@Tag(name = "Terminology", description = "What this installation calls organizations and severities")
@SecurityRequirement(name = "bearerAuth")
public class TerminologyConfigController {

    private final TerminologyConfigService service;

    @GetMapping
    @AuthenticatedOnly
    @Operation(summary = "Get the configured terminology",
               description = "Readable by any signed-in user: these labels appear on nearly every "
                       + "screen, so the interface cannot render correctly without them.")
    public ResponseEntity<JsonApiResponse<TerminologyConfig>> getConfig() {
        return ResponseUtil.success("Terminology retrieved successfully", service.getConfig());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('super_admin')")
    @Operation(summary = "Update the terminology",
               description = "Renames organizations, sub-organizations and severities throughout "
                       + "the interface. Only the wording changes: findings keep the severity they "
                       + "were recorded at, and every filter, report token and export still uses "
                       + "CRITICAL..INFORMATIONAL. A field that is omitted or empty keeps the "
                       + "label it already had.")
    public ResponseEntity<JsonApiResponse<TerminologyConfig>> updateConfig(
            @RequestBody TerminologyConfigRequest request) {
        return ResponseUtil.success("Terminology updated successfully", service.updateConfig(request));
    }
}
