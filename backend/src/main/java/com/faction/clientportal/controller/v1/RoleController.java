package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.CreateRoleRequest;
import com.faction.clientportal.edition.Feature;
import com.faction.clientportal.security.RequiresFeature;
import com.faction.clientportal.util.PageableUtil;
import com.faction.clientportal.util.PageableUtil.SortField;
import com.faction.clientportal.dto.RoleDto;
import com.faction.clientportal.dto.UpdateRoleRequest;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.RoleService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/roles")
@RequiredArgsConstructor
@Tag(name = "Roles", description = "Role management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class RoleController {

    private final RoleService roleService;

    /** Sortable role columns. Permissions is a list on the row, so there is nothing to order by. */
    private static final Map<String, SortField> SORTABLE_FIELDS = Map.of(
            "name", SortField.text("name"),
            "description", SortField.text("description"));

    private static final Sort DEFAULT_SORT =
            Sort.by("name");

    @GetMapping
    @RequiresPermission(Permission.ROLES_READ_ALL)
    @Operation(
            summary = "Get all roles",
            description = "Retrieve all roles with their assigned permissions. Supports pagination and optional search by name or description (case-insensitive). Only accessible to Super Admins.",
            parameters = {
                    @Parameter(name = "page", description = "Page number (0-indexed)", example = "0"),
                    @Parameter(name = "size", description = "Number of items per page", example = "10"),
                    @Parameter(name = "sort", description = "Sort field and direction (e.g., 'name,asc')", example = "name,asc"),
                    @Parameter(name = "search", description = "Search term to filter roles by name or description (case-insensitive)", example = "admin")
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved all roles",
                            content = @Content(schema = @Schema(implementation = ApiResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "403",
                            description = "Forbidden - User does not have super_admin permission"
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "Unauthorized - Invalid or missing JWT token"
                    )
            }
    )
    public ResponseEntity<JsonApiResponse<List<RoleDto>>> getAllRoles(
            @Parameter(hidden = true) @RequestParam(defaultValue = "0") int page,
            @Parameter(hidden = true) @RequestParam(defaultValue = "10") int size,
            @Parameter(hidden = true) @RequestParam(defaultValue = "name,asc") String sort,
            @Parameter(hidden = true) @RequestParam(required = false) String search) {

        Pageable pageable = PageableUtil.of(page, size, sort, DEFAULT_SORT, SORTABLE_FIELDS);
        Page<RoleDto> rolePage = roleService.searchRoles(search, pageable);

        return ResponseUtil.paginated("Roles retrieved successfully", rolePage);
    }

    @PostMapping
    @RequiresPermission(Permission.ROLES_CREATE_ALL)
    @RequiresFeature(Feature.CUSTOM_ROLES)
    @Operation(
            summary = "Create a new role",
            description = "Create a new role with specified permissions. Only accessible to Super Admins.",
            responses = {
                    @ApiResponse(
                            responseCode = "201",
                            description = "Role created successfully",
                            content = @Content(schema = @Schema(implementation = ApiResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Bad Request - Invalid input or role name already exists"
                    ),
                    @ApiResponse(
                            responseCode = "403",
                            description = "Forbidden - User does not have super_admin permission"
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "Unauthorized - Invalid or missing JWT token"
                    )
            }
    )
    public ResponseEntity<JsonApiResponse<RoleDto>> createRole(@Valid @RequestBody CreateRoleRequest request) {
        RoleDto createdRole = roleService.createRole(request);
        return ResponseUtil.created("Role created successfully", createdRole);
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.ROLES_EDIT_ALL)
    @RequiresFeature(Feature.CUSTOM_ROLES)
    @Operation(
            summary = "Update an existing role",
            description = "Update an existing role's name, description, or permissions. Only accessible to Super Admins.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Role updated successfully",
                            content = @Content(schema = @Schema(implementation = ApiResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Bad Request - Invalid input or role name already exists"
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Not Found - Role with specified ID does not exist"
                    ),
                    @ApiResponse(
                            responseCode = "403",
                            description = "Forbidden - User does not have super_admin permission"
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "Unauthorized - Invalid or missing JWT token"
                    )
            }
    )
    public ResponseEntity<JsonApiResponse<RoleDto>> updateRole(
            @PathVariable String id,
            @Valid @RequestBody UpdateRoleRequest request) {
        RoleDto updatedRole = roleService.updateRole(id, request);
        return ResponseUtil.success("Role updated successfully", updatedRole);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.ROLES_DELETE_ALL)
    @RequiresFeature(Feature.CUSTOM_ROLES)
    @Operation(
            summary = "Delete a role",
            description = "Delete a role. Default roles (SuperAdmin and Pentester) cannot be deleted. Only accessible to Super Admins.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Role deleted successfully",
                            content = @Content(schema = @Schema(implementation = ApiResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Bad Request - Cannot delete default roles"
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Not Found - Role with specified ID does not exist"
                    ),
                    @ApiResponse(
                            responseCode = "403",
                            description = "Forbidden - User does not have super_admin permission"
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "Unauthorized - Invalid or missing JWT token"
                    )
            }
    )
    public ResponseEntity<JsonApiResponse<Void>> deleteRole(@PathVariable String id) {
        roleService.deleteRole(id);
        return ResponseUtil.success("Role deleted successfully");
    }
}
