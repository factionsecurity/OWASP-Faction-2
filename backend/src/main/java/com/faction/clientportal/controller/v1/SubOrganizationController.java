package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.SubOrganizationDto;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.service.SubOrganizationService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Divisions within an organization. Nested under the organization because a sub-organization has
 * no meaning without its parent, and the gates mirror the organization's own: reading needs any
 * organization read scope, and adding, renaming or deleting one needs the matching organization
 * write permission — "organizational access" governs both.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/sub-organizations")
@RequiredArgsConstructor
@Tag(name = "Sub-Organizations", description = "Divisions within an organization")
@SecurityRequirement(name = "bearerAuth")
public class SubOrganizationController {

    private final SubOrganizationService subOrganizationService;

    @GetMapping
    @RequiresPermission({Permission.ORGANIZATIONS_READ_ALL, Permission.ORGANIZATIONS_READ_OWNED,
            Permission.ORGANIZATIONS_READ_ORG})
    @Operation(
        summary = "List an organization's sub-organizations",
        description = "Returns the organization's divisions, each with the number of applications "
                + "attributed to it.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Sub-organizations retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Organization not found"),
        }
    )
    public ResponseEntity<JsonApiResponse<List<SubOrganizationDto>>> list(@PathVariable String organizationId) {
        return ResponseUtil.success("Sub-organizations retrieved successfully",
                subOrganizationService.listForOrganization(organizationId));
    }

    @PostMapping
    @RequiresPermission({Permission.ORGANIZATIONS_CREATE_ALL, Permission.ORGANIZATIONS_EDIT_ALL})
    @Operation(
        summary = "Add a sub-organization",
        description = "Creates a division within the organization. Names are unique per organization.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Sub-organization created successfully"),
            @ApiResponse(responseCode = "400", description = "Name missing or already used in this organization"),
            @ApiResponse(responseCode = "404", description = "Organization not found"),
        }
    )
    public ResponseEntity<JsonApiResponse<SubOrganizationDto>> create(
            @PathVariable String organizationId,
            @Valid @RequestBody SubOrganizationDto.Request request,
            Authentication authentication) {
        return ResponseUtil.success("Sub-organization created successfully",
                subOrganizationService.create(organizationId, request, authentication.getName()));
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.ORGANIZATIONS_EDIT_ALL)
    @Operation(
        summary = "Rename a sub-organization",
        responses = {
            @ApiResponse(responseCode = "200", description = "Sub-organization updated successfully"),
            @ApiResponse(responseCode = "400", description = "Name missing or already used in this organization"),
            @ApiResponse(responseCode = "404", description = "Organization or sub-organization not found"),
        }
    )
    public ResponseEntity<JsonApiResponse<SubOrganizationDto>> update(
            @PathVariable String organizationId,
            @PathVariable String id,
            @Valid @RequestBody SubOrganizationDto.Request request,
            Authentication authentication) {
        return ResponseUtil.success("Sub-organization updated successfully",
                subOrganizationService.update(organizationId, id, request, authentication.getName()));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission({Permission.ORGANIZATIONS_DELETE_ALL, Permission.ORGANIZATIONS_EDIT_ALL})
    @Operation(
        summary = "Delete a sub-organization",
        description = "Refused while applications are still attributed to it — reassign them first.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Sub-organization deleted successfully"),
            @ApiResponse(responseCode = "409", description = "Applications are still assigned to it"),
            @ApiResponse(responseCode = "404", description = "Organization or sub-organization not found"),
        }
    )
    public ResponseEntity<JsonApiResponse<Map<String, String>>> delete(
            @PathVariable String organizationId,
            @PathVariable String id) {
        subOrganizationService.delete(organizationId, id);
        return ResponseUtil.success("Sub-organization deleted successfully",
                Map.of("id", id, "status", "deleted"));
    }
}
