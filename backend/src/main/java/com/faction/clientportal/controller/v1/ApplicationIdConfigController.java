package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.ApplicationIdConfigDto;
import com.faction.clientportal.dto.ApplicationIdConfigUpdateRequest;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.ApplicationIdConfigService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/application-id-config")
@PreAuthorize("hasAuthority('super_admin')")
@Tag(name = "Application ID Configuration", description = "Configure the auto-generated application ID format (Super Admin only)")
@SecurityRequirement(name = "bearerAuth")
public class ApplicationIdConfigController {

    private final ApplicationIdConfigService configService;

    public ApplicationIdConfigController(ApplicationIdConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    @Operation(summary = "Get application ID configuration",
               description = "Retrieve the current application ID generation settings (prefix, padding, next sequence value).")
    public ResponseEntity<JsonApiResponse<ApplicationIdConfigDto>> getConfig() {
        ApplicationIdConfigDto config = configService.getConfig();
        return ResponseUtil.success("Configuration retrieved", config);
    }

    @PutMapping
    @Operation(summary = "Update application ID configuration",
               description = "Update the application ID generation settings.")
    public ResponseEntity<JsonApiResponse<ApplicationIdConfigDto>> updateConfig(
            @Valid @RequestBody ApplicationIdConfigUpdateRequest request) {
        ApplicationIdConfigDto updated = configService.updateConfig(request);
        return ResponseUtil.success("Configuration updated", updated);
    }

    @GetMapping("/next")
    @Operation(summary = "Generate next application ID",
               description = "Generates and consumes the next application ID in the sequence.")
    public ResponseEntity<JsonApiResponse<String>> getNextId() {
        String nextId = configService.generateNextAppId();
        return ResponseUtil.success("Next ID generated", nextId);
    }

    @GetMapping("/preview")
    @Operation(summary = "Preview upcoming application IDs",
               description = "Returns the next IDs in the sequence without consuming them.",
               parameters = @Parameter(name = "count", description = "Number of IDs to preview", example = "5"))
    public ResponseEntity<JsonApiResponse<List<String>>> previewNext(@RequestParam(defaultValue = "5") int count) {
        List<String> preview = configService.getPreviewNext(count);
        return ResponseUtil.success("Preview generated", preview);
    }
}
