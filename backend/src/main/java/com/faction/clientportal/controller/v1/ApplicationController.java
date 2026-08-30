package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.ApplicationImportResultDto;
import com.faction.clientportal.model.ApplicationStatus;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.util.PageableUtil;
import com.faction.clientportal.util.PageableUtil.SortField;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.dto.*;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.ApplicationCsvImportService;
import com.faction.clientportal.service.ApplicationService;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/applications")
@RequiredArgsConstructor
@Tag(name = "Applications", description = "Application management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class ApplicationController {

    private final ApplicationService applicationService;
    private final ApplicationCsvImportService applicationCsvImportService;

    /**
     * Sortable application columns. Technologies (a list on the row) and Open Issues (an aggregate
     * batched in after the page is fetched) have no column to order by; Organization is shown from
     * a client-side id → name lookup, so it is not orderable here either.
     */
    private static final Map<String, SortField> SORTABLE_FIELDS = Map.of(
            "appId", SortField.text("appId"),
            "name", SortField.text("name"),
            "ownerName", SortField.text("ownerName"),
            "status", SortField.value("status"),
            "lastAssessmentDate", SortField.value("lastAssessmentDate"),
            "createdAt", SortField.value("createdAt"));

    private static final Sort DEFAULT_SORT = Sort.by("name");

    @GetMapping
    @RequiresPermission({Permission.APPLICATIONS_READ_ALL, Permission.APPLICATIONS_READ_OWNED, Permission.APPLICATIONS_READ_ORG})
    @Operation(
            summary = "Get all applications",
            description = "Retrieve all applications with pagination and optional search.",
            parameters = {
                    @Parameter(name = "page", description = "Page number (0-indexed)", example = "0"),
                    @Parameter(name = "size", description = "Number of items per page", example = "10"),
                    @Parameter(name = "search", description = "Search query for name or description", example = "Banking"),
                    @Parameter(name = "sort", description = "Sort field and direction (e.g., 'name,asc')", example = "name,asc"),
                    @Parameter(name = "organizationId", description = "Only applications owned by this organization"),
                    @Parameter(name = "subOrganizationId", description = "Only applications attributed to this division"),
                    @Parameter(name = "status", description = "Only applications in this lifecycle status", example = "PRODUCTION")
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved all applications",
                            content = @Content(schema = @Schema(implementation = ApiResponse.class))
                    ),
                    @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
            }
    )
    public ResponseEntity<JsonApiResponse<List<ApplicationDto>>> getAllApplications(
            @Parameter(hidden = true) @RequestParam(defaultValue = "0") int page,
            @Parameter(hidden = true) @RequestParam(defaultValue = "10") int size,
            @Parameter(hidden = true) @RequestParam(required = false) String search,
            @Parameter(hidden = true) @RequestParam(defaultValue = "name,asc") String sort,
            @Parameter(hidden = true) @RequestParam(required = false) String organizationId,
            @Parameter(hidden = true) @RequestParam(required = false) String subOrganizationId,
            @Parameter(hidden = true) @RequestParam(required = false) String status,
            Authentication authentication) {

        Pageable pageable = PageableUtil.of(page, size, sort, DEFAULT_SORT, SORTABLE_FIELDS);
        Page<ApplicationDto> applicationPage = applicationService.searchApplications(
                search, organizationId, subOrganizationId, parseStatus(status), pageable, authentication);

        return ResponseUtil.paginated("Applications retrieved successfully", applicationPage);
    }

    /** Blank/absent → no status filter; an unknown value is a 400 rather than an empty list. */
    private static ApplicationStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ApplicationStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown application status '" + status + "'. Expected one of: "
                    + java.util.Arrays.toString(ApplicationStatus.values()));
        }
    }

    // ── CSV sync (admin only) ────────────────────────────────────────────────

    @GetMapping(value = "/import/template", produces = MediaType.TEXT_PLAIN_VALUE)
    @PreAuthorize("hasAuthority('super_admin')")
    @Operation(
            summary = "Download the application CSV template",
            description = "The column layout the sync accepts, with one example row filled in.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Template returned"),
                    @ApiResponse(responseCode = "403", description = "Forbidden - Super Admins only"),
            }
    )
    public ResponseEntity<String> downloadImportTemplate() {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=application-import-template.csv")
                .body(applicationCsvImportService.template());
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('super_admin')")
    @Operation(
            summary = "Sync applications from a CSV",
            description = "Upserts one application per row: matched by appId, then by name, and "
                    + "inserted when neither matches. Organizations and sub-organizations named in "
                    + "a row are created if they don't exist. Rows that fail are reported with "
                    + "their line number; the rest are still applied.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Sync finished (see the result for per-row failures)"),
                    @ApiResponse(responseCode = "400", description = "The file is missing, empty, or has unknown columns"),
                    @ApiResponse(responseCode = "403", description = "Forbidden - Super Admins only"),
            }
    )
    public ResponseEntity<JsonApiResponse<ApplicationImportResultDto>> importApplications(
            @RequestParam("file") MultipartFile file,
            Authentication authentication) throws IOException {
        ApplicationImportResultDto result =
                applicationCsvImportService.importCsv(file, authentication.getName());
        return ResponseUtil.success("Applications synced successfully", result);
    }

    @GetMapping("/inventory-search")
    @RequiresPermission({Permission.APPLICATIONS_READ_ALL, Permission.APPLICATIONS_READ_OWNED, Permission.APPLICATIONS_READ_ORG})
    @Operation(
            summary = "Search external application inventory",
            description = "Queries installed ApplicationInventory extensions for applications held "
                    + "in an external system of record. Returns an empty list when no such "
                    + "extension is installed. These are not Faction applications and have no "
                    + "Faction id — use them to prefill a new application.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Inventory results (possibly empty)"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<List<ExternalApplicationDto>>> searchInventory(
            @RequestParam(required = false) String applicationId,
            @RequestParam(required = false) String name) {
        return ResponseUtil.success("Inventory search complete",
                applicationService.searchExternalInventory(applicationId, name));
    }

    @GetMapping("/{id}")
    @RequiresPermission({Permission.APPLICATIONS_READ_ALL, Permission.APPLICATIONS_READ_OWNED, Permission.APPLICATIONS_READ_ORG})
    @Operation(
            summary = "Get application by ID",
            description = "Retrieve a specific application by its ID.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Successfully retrieved application"),
                    @ApiResponse(responseCode = "404", description = "Application not found"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<ApplicationDto>> getApplicationById(@PathVariable String id, Authentication authentication) {
        ApplicationDto application = applicationService.findById(id, authentication);
        return ResponseUtil.success("Application retrieved successfully", application);
    }

    @GetMapping("/organization/{organizationId}")
    @PreAuthorize("hasAnyAuthority('super_admin', 'applications:read:all')")
    @Operation(
            summary = "Get applications by organization",
            description = "Retrieve all applications for a specific organization. Only accessible to Super Admins.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Successfully retrieved applications"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<List<ApplicationDto>>> getApplicationsByOrganization(
            @PathVariable String organizationId) {
        List<ApplicationDto> applications = applicationService.findByOrganizationId(organizationId);
        return ResponseUtil.success("Applications retrieved successfully", applications);
    }

    @PostMapping
    @RequiresPermission({Permission.APPLICATIONS_CREATE_ALL, Permission.APPLICATIONS_CREATE_OWNED, Permission.APPLICATIONS_CREATE_ORG})
    @Operation(
            summary = "Create a new application",
            description = "Create a new application with specified details.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Application created successfully"),
                    @ApiResponse(responseCode = "400", description = "Bad Request - Invalid input"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<ApplicationDto>> createApplication(
            @Valid @RequestBody CreateApplicationRequest request,
            Authentication authentication) {
        String userId = authentication.getName();
        ApplicationDto createdApplication = applicationService.createApplication(request, userId, authentication);
        return ResponseUtil.created("Application created successfully", createdApplication);
    }

    @PutMapping("/{id}")
    @RequiresPermission({Permission.APPLICATIONS_EDIT_ALL, Permission.APPLICATIONS_READ_OWNED, Permission.APPLICATIONS_READ_ORG, Permission.APPLICATIONS_EDIT_ORG})
    @Operation(
            summary = "Update an existing application",
            description = "Update an existing application's details.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Application updated successfully"),
                    @ApiResponse(responseCode = "400", description = "Bad Request - Invalid input"),
                    @ApiResponse(responseCode = "404", description = "Application not found"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<ApplicationDto>> updateApplication(
            @PathVariable String id,
            @Valid @RequestBody UpdateApplicationRequest request,
            Authentication authentication) {
        String userId = authentication.getName();
        ApplicationDto updatedApplication = applicationService.updateApplication(id, request, userId, authentication);
        return ResponseUtil.success("Application updated successfully", updatedApplication);
    }

    @PutMapping("/{id}/move/{newOrganizationId}")
    @PreAuthorize("hasAuthority('super_admin')")
    @Operation(
            summary = "Move application to another organization",
            description = "Move an application from one organization to another. Only accessible to Super Admins.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Application moved successfully"),
                    @ApiResponse(responseCode = "404", description = "Application or Organization not found"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<ApplicationDto>> moveApplicationToOrganization(
            @PathVariable String id,
            @PathVariable String newOrganizationId,
            Authentication authentication) {
        String userId = authentication.getName();
        ApplicationDto movedApplication = applicationService.moveApplicationToOrganization(id, newOrganizationId, userId);
        return ResponseUtil.success("Application moved successfully", movedApplication);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.APPLICATIONS_DELETE_ALL)
    @Operation(
            summary = "Delete an application",
            description = "Delete an application.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Application deleted successfully"),
                    @ApiResponse(responseCode = "404", description = "Application not found"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<Void>> deleteApplication(@PathVariable String id) {
        applicationService.deleteApplication(id);
        return ResponseUtil.success("Application deleted successfully");
    }

    // ==================== ASSIGNED USER ENDPOINTS ====================

    @GetMapping("/{id}/users")
    @RequiresPermission(Permission.APPLICATIONS_EDIT_ALL)
    @Operation(summary = "Get assigned users for application")
    public ResponseEntity<JsonApiResponse<List<AssignedUserDto>>> getAssignedUsers(@PathVariable String id) {
        List<AssignedUserDto> users = applicationService.getAssignedUsers(id);
        return ResponseUtil.success("Assigned users retrieved successfully", users);
    }

    @PostMapping("/{id}/users")
    @RequiresPermission(Permission.APPLICATIONS_EDIT_ALL)
    @Operation(summary = "Assign a user to application")
    public ResponseEntity<JsonApiResponse<AssignedUserDto>> assignUser(
            @PathVariable String id,
            @Valid @RequestBody AssignUserRequest request,
            Authentication authentication) {
        AssignedUserDto dto = applicationService.assignUser(id, request, authentication.getName());
        return ResponseUtil.created("User assigned successfully", dto);
    }

    @PutMapping("/{id}/users/{userId}")
    @RequiresPermission(Permission.APPLICATIONS_EDIT_ALL)
    @Operation(summary = "Update assigned user access level")
    public ResponseEntity<JsonApiResponse<AssignedUserDto>> updateAssignedUser(
            @PathVariable String id,
            @PathVariable String userId,
            @Valid @RequestBody UpdateAssignedUserRequest request,
            Authentication authentication) {
        AssignedUserDto dto = applicationService.updateAssignedUser(id, userId, request, authentication.getName());
        return ResponseUtil.success("Assigned user updated successfully", dto);
    }

    @DeleteMapping("/{id}/users/{userId}")
    @RequiresPermission(Permission.APPLICATIONS_EDIT_ALL)
    @Operation(summary = "Remove assigned user from application")
    public ResponseEntity<JsonApiResponse<Void>> removeAssignedUser(
            @PathVariable String id,
            @PathVariable String userId) {
        applicationService.removeAssignedUser(id, userId);
        return ResponseUtil.success("User removed successfully");
    }

    // ==================== THREAD SUBSCRIBER ENDPOINTS ====================
    // Same permissions as commenting: if you may take part in the discussion, you may
    // choose to follow it and bring colleagues in.

    @GetMapping("/{id}/subscribers")
    @RequiresPermission({Permission.APPLICATIONS_READ_ALL, Permission.APPLICATIONS_READ_OWNED, Permission.APPLICATIONS_READ_ORG, Permission.APPLICATIONS_EDIT_ALL, Permission.APPLICATIONS_EDIT_ORG})
    @Operation(summary = "List the users following an application's discussion")
    public ResponseEntity<JsonApiResponse<List<String>>> getSubscribers(
            @PathVariable String id,
            Authentication authentication) {
        return ResponseUtil.success("Subscribers retrieved",
                applicationService.getSubscribers(id, authentication));
    }

    @PostMapping("/{id}/subscribers/{username}")
    @RequiresPermission({Permission.APPLICATIONS_READ_ALL, Permission.APPLICATIONS_READ_OWNED, Permission.APPLICATIONS_READ_ORG, Permission.APPLICATIONS_EDIT_ALL, Permission.APPLICATIONS_EDIT_ORG})
    @Operation(summary = "Add a user to an application's discussion")
    public ResponseEntity<JsonApiResponse<List<String>>> addSubscriber(
            @PathVariable String id,
            @PathVariable String username,
            Authentication authentication) {
        return ResponseUtil.success("Subscriber added",
                applicationService.addSubscriber(id, username, authentication));
    }

    @DeleteMapping("/{id}/subscribers/{username}")
    @RequiresPermission({Permission.APPLICATIONS_READ_ALL, Permission.APPLICATIONS_READ_OWNED, Permission.APPLICATIONS_READ_ORG, Permission.APPLICATIONS_EDIT_ALL, Permission.APPLICATIONS_EDIT_ORG})
    @Operation(summary = "Remove a user from an application's discussion")
    public ResponseEntity<JsonApiResponse<List<String>>> removeSubscriber(
            @PathVariable String id,
            @PathVariable String username,
            Authentication authentication) {
        return ResponseUtil.success("Subscriber removed",
                applicationService.removeSubscriber(id, username, authentication));
    }

    // ==================== COMMENT ENDPOINTS ====================

    @PostMapping("/{id}/comments")
    @RequiresPermission({Permission.APPLICATIONS_READ_ALL, Permission.APPLICATIONS_READ_OWNED, Permission.APPLICATIONS_READ_ORG, Permission.APPLICATIONS_EDIT_ALL, Permission.APPLICATIONS_EDIT_ORG})
    @Operation(summary = "Add a comment to an application")
    public ResponseEntity<JsonApiResponse<List<ApplicationCommentDto>>> addComment(
            @PathVariable String id,
            @Valid @RequestBody AddCommentRequest request,
            Authentication authentication) {
        return ResponseUtil.success("Comment added",
                applicationService.addComment(id, request, authentication.getName(), authentication));
    }

    @DeleteMapping("/{id}/comments/{commentId}")
    @RequiresPermission({Permission.APPLICATIONS_READ_ALL, Permission.APPLICATIONS_READ_OWNED, Permission.APPLICATIONS_READ_ORG, Permission.APPLICATIONS_EDIT_ALL, Permission.APPLICATIONS_EDIT_ORG})
    @Operation(summary = "Delete a comment from an application")
    public ResponseEntity<JsonApiResponse<List<ApplicationCommentDto>>> deleteComment(
            @PathVariable String id,
            @PathVariable String commentId,
            Authentication authentication) {
        return ResponseUtil.success("Comment deleted",
                applicationService.deleteComment(id, commentId, authentication.getName(), authentication));
    }
}
