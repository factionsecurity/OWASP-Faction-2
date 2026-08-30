package com.faction.clientportal.controller.v1;

import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.dto.ResourcePermissionsDto;
import com.faction.clientportal.service.PermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/permissions")
@RequiredArgsConstructor
@Tag(name = "Permissions", description = "Permission management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class PermissionController {

    private final PermissionService permissionService;

    @GetMapping
    @RequiresPermission(Permission.ROLES_READ_ALL)
    @Operation(
            summary = "Get all permissions",
            description = "Retrieves all available permissions organized by resource. Used for role management UI."
    )
    public ResponseEntity<JsonApiResponse<List<ResourcePermissionsDto>>> getAllPermissions() {
        List<ResourcePermissionsDto> permissions = permissionService.getAllPermissionsByResource();
        return ResponseEntity.ok(JsonApiResponse.success(permissions));
    }
}
