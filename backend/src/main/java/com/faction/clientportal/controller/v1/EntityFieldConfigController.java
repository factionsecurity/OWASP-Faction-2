package com.faction.clientportal.controller.v1;

import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.dto.EntityFieldConfigDto;
import com.faction.clientportal.dto.UpdateEntityFieldConfigRequest;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.model.FieldScope;
import com.faction.clientportal.service.EntityFieldConfigService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/entity-fields")
@RequiredArgsConstructor
@Tag(name = "Entity Field Configs", description = "Manage custom field definitions for Applications and Organizations")
@SecurityRequirement(name = "bearerAuth")
public class EntityFieldConfigController {

    private final EntityFieldConfigService entityFieldConfigService;

    @GetMapping("/{scope}")
    @RequiresPermission({Permission.APPLICATIONS_READ_ALL, Permission.APPLICATIONS_READ_OWNED, Permission.ORGANIZATIONS_READ_ALL, Permission.ORGANIZATIONS_READ_OWNED})
    @Operation(
            summary = "Get field definitions for a scope",
            description = "Retrieve the custom field definitions for APPLICATION or ORGANIZATION scope.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Field config retrieved successfully"),
                    @ApiResponse(responseCode = "400", description = "Invalid scope"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized"),
                    @ApiResponse(responseCode = "403", description = "Forbidden")
            }
    )
    public ResponseEntity<JsonApiResponse<EntityFieldConfigDto>> getFieldConfig(@PathVariable FieldScope scope) {
        EntityFieldConfigDto config = entityFieldConfigService.getFieldConfig(scope);
        return ResponseUtil.success("Field config retrieved successfully", config);
    }

    @PutMapping("/{scope}")
    @PreAuthorize("hasAuthority('super_admin')")
    @Operation(
            summary = "Update field definitions for a scope",
            description = "Update the custom field definitions for APPLICATION or ORGANIZATION scope. Only accessible to Super Admins.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Field config updated successfully"),
                    @ApiResponse(responseCode = "400", description = "Invalid scope or request"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized"),
                    @ApiResponse(responseCode = "403", description = "Forbidden")
            }
    )
    public ResponseEntity<JsonApiResponse<EntityFieldConfigDto>> updateFieldConfig(
            @PathVariable FieldScope scope,
            @RequestBody UpdateEntityFieldConfigRequest request,
            Authentication authentication) {
        String userId = authentication.getName();
        EntityFieldConfigDto config = entityFieldConfigService.updateFieldConfig(scope, request, userId);
        return ResponseUtil.success("Field config updated successfully", config);
    }
}
