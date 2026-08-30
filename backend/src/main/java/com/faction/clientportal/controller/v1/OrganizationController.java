package com.faction.clientportal.controller.v1;

import com.faction.clientportal.model.Permission;
import com.faction.clientportal.util.PageableUtil;
import com.faction.clientportal.util.PageableUtil.SortField;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.dto.*;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.OrganizationService;
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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
@Tag(name = "Organizations", description = "Organization management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class OrganizationController {

    private final OrganizationService organizationService;

    /** Sortable organization columns. */
    private static final Map<String, SortField> SORTABLE_FIELDS = Map.of(
            "name", SortField.text("name"),
            "description", SortField.text("description"));

    private static final Sort DEFAULT_SORT = Sort.by("name");

    @GetMapping
    @RequiresPermission({Permission.ORGANIZATIONS_READ_ALL, Permission.ORGANIZATIONS_READ_OWNED, Permission.ORGANIZATIONS_READ_ORG})
    @Operation(
            summary = "Get all organizations",
            description = "Retrieve all organizations with pagination and optional search.",
            parameters = {
                    @Parameter(name = "page", description = "Page number (0-indexed)", example = "0"),
                    @Parameter(name = "size", description = "Number of items per page", example = "10"),
                    @Parameter(name = "sort", description = "Sort field and direction (e.g., 'name,asc')", example = "name,asc"),
                    @Parameter(name = "search", description = "Search term for name or description", example = "acme")
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved all organizations",
                            content = @Content(schema = @Schema(implementation = ApiResponse.class))
                    ),
                    @ApiResponse(responseCode = "403", description = "Forbidden - User does not have super_admin permission"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
            }
    )
    public ResponseEntity<JsonApiResponse<List<OrganizationDto>>> getAllOrganizations(
            @Parameter(hidden = true) @RequestParam(defaultValue = "0") int page,
            @Parameter(hidden = true) @RequestParam(defaultValue = "10") int size,
            @Parameter(hidden = true) @RequestParam(defaultValue = "name,asc") String sort,
            @Parameter(hidden = true) @RequestParam(required = false) String search,
            Authentication authentication) {

        Pageable pageable = PageableUtil.of(page, size, sort, DEFAULT_SORT, SORTABLE_FIELDS);
        Page<OrganizationDto> organizationPage = organizationService.searchOrganizations(search, pageable, authentication);

        return ResponseUtil.paginated("Organizations retrieved successfully", organizationPage);
    }

    @GetMapping("/{id}")
    @RequiresPermission({Permission.ORGANIZATIONS_READ_ALL, Permission.ORGANIZATIONS_READ_OWNED, Permission.ORGANIZATIONS_READ_ORG})
    @Operation(
            summary = "Get organization by ID",
            description = "Retrieve a specific organization by its ID.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Successfully retrieved organization"),
                    @ApiResponse(responseCode = "404", description = "Organization not found"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<OrganizationDto>> getOrganizationById(@PathVariable String id, Authentication authentication) {
        OrganizationDto organization = organizationService.findOrganizationById(id, authentication);
        return ResponseUtil.success("Organization retrieved successfully", organization);
    }

    @PostMapping
    @RequiresPermission(Permission.ORGANIZATIONS_CREATE_ALL)
    @Operation(
            summary = "Create a new organization",
            description = "Create a new organization with specified details.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Organization created successfully"),
                    @ApiResponse(responseCode = "400", description = "Bad Request - Invalid input or name already exists"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<OrganizationDto>> createOrganization(
            @Valid @RequestBody CreateOrganizationRequest request) {
        OrganizationDto createdOrganization = organizationService.createOrganizationDto(request);
        return ResponseUtil.created("Organization created successfully", createdOrganization);
    }

    @PutMapping("/{id}")
    @RequiresPermission({Permission.ORGANIZATIONS_EDIT_ALL, Permission.ORGANIZATIONS_READ_OWNED})
    @Operation(
            summary = "Update an existing organization",
            description = "Update an existing organization's details.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Organization updated successfully"),
                    @ApiResponse(responseCode = "400", description = "Bad Request - Invalid input or name already exists"),
                    @ApiResponse(responseCode = "404", description = "Organization not found"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<OrganizationDto>> updateOrganization(
            @PathVariable String id,
            @Valid @RequestBody UpdateOrganizationRequest request,
            Authentication authentication) {
        OrganizationDto updatedOrganization = organizationService.updateOrganizationDto(id, request, authentication);
        return ResponseUtil.success("Organization updated successfully", updatedOrganization);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.ORGANIZATIONS_DELETE_ALL)
    @Operation(
            summary = "Delete an organization",
            description = "Delete an organization. Cannot delete if applications are assigned.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Organization deleted successfully"),
                    @ApiResponse(responseCode = "400", description = "Bad Request - Organization has assigned applications"),
                    @ApiResponse(responseCode = "404", description = "Organization not found"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<Void>> deleteOrganization(@PathVariable String id) {
        organizationService.deleteOrganizationById(id);
        return ResponseUtil.success("Organization deleted successfully");
    }

    // ==================== ASSIGNED USER ENDPOINTS ====================

    @GetMapping("/{id}/users")
    @RequiresPermission(Permission.ORGANIZATIONS_EDIT_ALL)
    @Operation(summary = "Get assigned users for organization")
    public ResponseEntity<JsonApiResponse<List<AssignedUserDto>>> getAssignedUsers(@PathVariable String id) {
        List<AssignedUserDto> users = organizationService.getAssignedUsers(id);
        return ResponseUtil.success("Assigned users retrieved successfully", users);
    }

    @PostMapping("/{id}/users")
    @RequiresPermission(Permission.ORGANIZATIONS_EDIT_ALL)
    @Operation(summary = "Assign a user to organization")
    public ResponseEntity<JsonApiResponse<AssignedUserDto>> assignUser(
            @PathVariable String id,
            @Valid @RequestBody AssignUserRequest request,
            Authentication authentication) {
        AssignedUserDto dto = organizationService.assignUser(id, request, authentication.getName());
        return ResponseUtil.created("User assigned successfully", dto);
    }

    @PutMapping("/{id}/users/{userId}")
    @RequiresPermission(Permission.ORGANIZATIONS_EDIT_ALL)
    @Operation(summary = "Update assigned user access level")
    public ResponseEntity<JsonApiResponse<AssignedUserDto>> updateAssignedUser(
            @PathVariable String id,
            @PathVariable String userId,
            @Valid @RequestBody UpdateAssignedUserRequest request,
            Authentication authentication) {
        AssignedUserDto dto = organizationService.updateAssignedUser(id, userId, request, authentication.getName());
        return ResponseUtil.success("Assigned user updated successfully", dto);
    }

    @DeleteMapping("/{id}/users/{userId}")
    @RequiresPermission(Permission.ORGANIZATIONS_EDIT_ALL)
    @Operation(summary = "Remove assigned user from organization")
    public ResponseEntity<JsonApiResponse<Void>> removeAssignedUser(
            @PathVariable String id,
            @PathVariable String userId) {
        organizationService.removeAssignedUser(id, userId);
        return ResponseUtil.success("User removed successfully");
    }
}
