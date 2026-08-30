package com.faction.clientportal.controller.v1;

import com.faction.clientportal.model.Permission;
import com.faction.clientportal.util.PageableUtil;
import com.faction.clientportal.util.PageableUtil.SortField;
import com.faction.clientportal.model.User;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.dto.*;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.AssessmentLockService;
import com.faction.clientportal.service.AssessmentService;
import com.faction.clientportal.service.ReportGenerationService;
import com.faction.clientportal.service.StorageService;
import com.faction.clientportal.service.UserService;
import com.faction.clientportal.util.FileStreamResponse;
import com.faction.clientportal.util.ResponseUtil;
import com.faction.clientportal.util.UploadRequests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;

@RestController
@RequestMapping("/api/v1/assessments")
@RequiredArgsConstructor
@Tag(name = "Assessments", description = "Assessment management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class AssessmentController {

    private final AssessmentService assessmentService;
    private final AssessmentLockService assessmentLockService;
    private final UserService userService;
    private final ReportGenerationService reportGenerationService;
    private final UploadRequests uploadRequests;

    /**
     * Sortable assessment columns → the keys the search query's ORDER BY whitelist resolves. The
     * related-entity names each add a LEFT JOIN in the query rather than sorting by raw foreign key,
     * so "Application" orders by the application's name and not its id. Assessors (a JSONB id list
     * resolved per row) and the Vulnerabilities counts (per-severity aggregates) are not columns and
     * are therefore not offered.
     */
    private static final Map<String, SortField> SORTABLE_FIELDS = Map.ofEntries(
            Map.entry("name", SortField.text("name")),
            Map.entry("status", SortField.text("status")),
            Map.entry("startDate", SortField.value("startDate")),
            Map.entry("plannedEndDate", SortField.value("plannedEndDate")),
            Map.entry("completedDate", SortField.value("completedDate")),
            Map.entry("assessmentDate", SortField.value("assessmentDate")),
            Map.entry("createdAt", SortField.value("createdAt")),
            Map.entry("appId", SortField.text("appId")),
            Map.entry("applicationName", SortField.text("applicationName")),
            Map.entry("assessmentTypeName", SortField.text("assessmentTypeName")),
            Map.entry("teamName", SortField.text("teamName")),
            Map.entry("organizationName", SortField.text("organizationName")),
            Map.entry("campaignName", SortField.text("campaignName")));

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    @PostMapping
    @RequiresPermission({Permission.ASSESSMENTS_CREATE_ALL, Permission.ASSESSMENTS_CREATE_TEAM})
    @Operation(
        summary = "Create assessment",
        description = "Create a new assessment from a report template. Snapshots the template's field definitions.",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Assessment created successfully",
                content = @Content(schema = @Schema(implementation = AssessmentDto.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request - validation error or inactive template"),
            @ApiResponse(responseCode = "404", description = "Application, assessment type, or template not found"),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<JsonApiResponse<AssessmentDto>> createAssessment(
        @Valid @RequestBody CreateAssessmentRequest request,
        Authentication authentication
    ) {
        String userId = authentication.getName();
        AssessmentDto assessment = assessmentService.createAssessment(request, userId);
        return ResponseUtil.success("Assessment created successfully", assessment);
    }

    @GetMapping
    @RequiresPermission({Permission.ASSESSMENTS_READ_ALL, Permission.ASSESSMENTS_READ_TEAM, Permission.ASSESSMENTS_READ_ASSIGNED, Permission.ASSESSMENTS_READ_ORG, Permission.ASSESSMENTS_READ_OWNED})
    @Operation(
        summary = "Search assessments",
        description = "Search assessments with pagination and advanced filters",
        parameters = {
            @Parameter(name = "page", description = "Page number (0-indexed)", example = "0"),
            @Parameter(name = "size", description = "Number of items per page", example = "10"),
            @Parameter(name = "search", description = "Search across name, application, assessor (case-insensitive)", example = "Security"),
            @Parameter(name = "applicationId", description = "Filter by application ID"),
            @Parameter(name = "organizationId", description = "Filter by organization ID"),
            @Parameter(name = "assessmentTypeId", description = "Filter by assessment type ID"),
            @Parameter(name = "assessorId", description = "Filter by assessor user ID"),
            @Parameter(name = "status", description = "Filter by status", example = "IN_PROGRESS"),
            @Parameter(name = "statuses", description = "Filter by any of several statuses (repeatable or comma-separated)", example = "IN_PROGRESS,ON_HOLD"),
            @Parameter(name = "openSurveys", description = "Only assessments with at least one unfinished survey", example = "true"),
            @Parameter(name = "startDateFrom", description = "Filter by start date from (ISO format)", example = "2024-01-01T00:00:00"),
            @Parameter(name = "startDateTo", description = "Filter by start date to (ISO format)", example = "2024-12-31T23:59:59"),
            @Parameter(name = "endDateFrom", description = "Filter by end date from (ISO format)", example = "2024-01-01T00:00:00"),
            @Parameter(name = "endDateTo", description = "Filter by end date to (ISO format)", example = "2024-12-31T23:59:59"),
            @Parameter(name = "pastDue", description = "Filter for past due assessments only", example = "true"),
            @Parameter(name = "showCompleted", description = "Include completed/approved/archived assessments", example = "false"),
            @Parameter(name = "assignedToMe", description = "Show only assessments assigned to current user", example = "true"),
            @Parameter(name = "sort", description = "Sort field and direction", example = "createdAt,desc")
        },
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Successfully retrieved assessments",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))
            ),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<JsonApiResponse<List<AssessmentDto>>> searchAssessments(
        @Parameter(hidden = true) @RequestParam(defaultValue = "0") int page,
        @Parameter(hidden = true) @RequestParam(defaultValue = "10") int size,
        @Parameter(hidden = true) @RequestParam(required = false) String search,
        @Parameter(hidden = true) @RequestParam(required = false) String applicationId,
        @Parameter(hidden = true) @RequestParam(required = false) List<String> applicationIds,
        @Parameter(hidden = true) @RequestParam(required = false) String organizationId,
        @Parameter(hidden = true) @RequestParam(required = false) String assessmentTypeId,
        @Parameter(hidden = true) @RequestParam(required = false) String assessorId,
        @Parameter(hidden = true) @RequestParam(required = false) String status,
        @Parameter(hidden = true) @RequestParam(required = false) List<String> statuses,
        @Parameter(hidden = true) @RequestParam(required = false) Boolean openSurveys,
        @Parameter(hidden = true) @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDateFrom,
        @Parameter(hidden = true) @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDateTo,
        @Parameter(hidden = true) @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDateFrom,
        @Parameter(hidden = true) @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDateTo,
        @Parameter(hidden = true) @RequestParam(required = false) Boolean pastDue,
        @Parameter(hidden = true) @RequestParam(required = false) Boolean showCompleted,
        @Parameter(hidden = true) @RequestParam(required = false) Boolean assignedToMe,
        @Parameter(hidden = true) @RequestParam(defaultValue = "createdAt,desc") String sort,
        Authentication authentication
    ) {
        Pageable pageable = PageableUtil.of(page, size, sort, DEFAULT_SORT, SORTABLE_FIELDS);

        // Resolve the current user's ID for the assignedToMe filter — the JWT
        // principal is the username, but assignments store user IDs, so passing
        // getName() straight through would never match.
        String currentUserId = authentication != null
            ? userService.findByUsername(authentication.getName())
                .map(User::getId)
                .orElse(null)
            : null;

        Page<AssessmentDto> assessments = assessmentService.searchAssessmentsAdvanced(
            search,
            applicationId,
            applicationIds,
            organizationId,
            assessmentTypeId,
            assessorId,
            status,
            statuses,
            openSurveys,
            startDateFrom,
            startDateTo,
            endDateFrom,
            endDateTo,
            pastDue,
            showCompleted,
            assignedToMe,
            currentUserId,
            null,
            null,
            null,
            pageable,
            authentication
        );

        return ResponseUtil.paginated("Assessments retrieved successfully", assessments);
    }

    @GetMapping("/summary")
    @RequiresPermission({Permission.ASSESSMENTS_READ_ALL, Permission.ASSESSMENTS_READ_TEAM, Permission.ASSESSMENTS_READ_ASSIGNED, Permission.ASSESSMENTS_READ_ORG, Permission.ASSESSMENTS_READ_OWNED})
    @Operation(summary = "Assessment summary counts",
            description = "Aggregate assessment counts (active / total) visible to the caller — a lightweight "
                    + "grouped query for the sidebar badge and dashboards (does not materialize the assessment list).")
    public ResponseEntity<JsonApiResponse<AssessmentSummaryDto>> getSummary(Authentication authentication) {
        return ResponseUtil.success(assessmentService.assessmentSummary(authentication));
    }

    @GetMapping("/{id}")
    @RequiresPermission({Permission.ASSESSMENTS_READ_ALL, Permission.ASSESSMENTS_READ_TEAM, Permission.ASSESSMENTS_READ_ASSIGNED, Permission.ASSESSMENTS_READ_ORG, Permission.ASSESSMENTS_READ_OWNED})
    @Operation(
        summary = "Get assessment by ID",
        description = "Retrieve a single assessment with full details including field definitions and values",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Successfully retrieved assessment",
                content = @Content(schema = @Schema(implementation = AssessmentDto.class))
            ),
            @ApiResponse(responseCode = "404", description = "Assessment not found"),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<JsonApiResponse<AssessmentDto>> getAssessment(@PathVariable String id,
            Authentication authentication) {
        AssessmentDto assessment = assessmentService.getAssessment(id, authentication);
        return ResponseUtil.success("Assessment retrieved successfully", assessment);
    }

    @GetMapping("/{id}/assignable-assessors")
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(
        summary = "List assignable assessors",
        description = "The users who can be added as assessors on this assessment: the members of the "
            + "assessment's team, or every internal user when the assessment has no team set. Gated on "
            + "assessment access rather than users:read, so an assessor editing their own assessment can "
            + "populate the picker without permission to browse the user directory.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Assignable assessors retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Assessment not found"),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<JsonApiResponse<List<AssignableUserDto>>> getAssignableAssessors(
        @PathVariable String id,
        Authentication authentication
    ) {
        return ResponseUtil.success("Assignable assessors retrieved successfully",
            assessmentService.getAssignableAssessors(id, authentication));
    }

    @PutMapping("/{id}")
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(
        summary = "Update assessment",
        description = "Update assessment field values and/or status. Field values are validated against field definitions.",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Assessment updated successfully",
                content = @Content(schema = @Schema(implementation = AssessmentDto.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request - validation error"),
            @ApiResponse(responseCode = "404", description = "Assessment not found"),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<JsonApiResponse<AssessmentDto>> updateAssessment(
        @PathVariable String id,
        @Valid @RequestBody UpdateAssessmentRequest request,
        Authentication authentication
    ) {
        String userId = authentication.getName();
        AssessmentDto assessment = assessmentService.updateAssessment(id, request, userId, authentication);
        if (request.getFieldValues() != null) {
            request.getFieldValues().forEach((fieldId, value) ->
                assessmentLockService.broadcastFieldUpdated(id, fieldId, value, userId));
        }
        return ResponseUtil.success("Assessment updated successfully", assessment);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission({Permission.ASSESSMENTS_DELETE_ALL, Permission.ASSESSMENTS_DELETE_TEAM})
    @Operation(
        summary = "Delete assessment",
        description = "Soft delete an assessment",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Assessment deleted successfully"
            ),
            @ApiResponse(responseCode = "404", description = "Assessment not found"),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<JsonApiResponse<Void>> deleteAssessment(
        @PathVariable String id,
        Authentication authentication
    ) {
        String userId = authentication.getName();
        assessmentService.deleteAssessment(id, userId, authentication);
        return ResponseUtil.success("Assessment deleted successfully", null);
    }

    @GetMapping("/by-application/{applicationId}")
    @RequiresPermission({Permission.ASSESSMENTS_READ_ALL, Permission.ASSESSMENTS_READ_ORG, Permission.ASSESSMENTS_READ_OWNED})
    @Operation(
        summary = "Get assessments by application",
        description = "Retrieve all assessments for a specific application",
        parameters = {
            @Parameter(name = "page", description = "Page number (0-indexed)", example = "0"),
            @Parameter(name = "size", description = "Number of items per page", example = "10")
        },
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Successfully retrieved assessments",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))
            ),
            @ApiResponse(responseCode = "404", description = "Application not found"),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<JsonApiResponse<List<AssessmentDto>>> getAssessmentsByApplication(
        @PathVariable String applicationId,
        @Parameter(hidden = true) @RequestParam(defaultValue = "0") int page,
        @Parameter(hidden = true) @RequestParam(defaultValue = "10") int size,
        Authentication authentication
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AssessmentDto> assessments = assessmentService.getAssessmentsByApplication(applicationId, pageable, authentication);
        return ResponseUtil.paginated("Assessments retrieved successfully", assessments);
    }

    @PostMapping("/{id}/validate")
    @RequiresPermission(Permission.ASSESSMENTS_EDIT_ALL)
    @Operation(
        summary = "Validate field values",
        description = "Validate field values against the assessment's field definitions without saving",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Field values are valid"
            ),
            @ApiResponse(responseCode = "400", description = "Validation error - invalid field values"),
            @ApiResponse(responseCode = "404", description = "Assessment not found"),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<JsonApiResponse<Map<String, String>>> validateFieldValues(
        @PathVariable String id,
        @RequestBody Map<String, String> fieldValues
    ) {
        AssessmentDto assessment = assessmentService.getAssessment(id);
        assessmentService.validateFieldValues(fieldValues, assessment.getFieldDefinitions().stream()
            .map(dto -> dto.toEntity())
            .toList());
        return ResponseUtil.success("Field values are valid", fieldValues);
    }

    @GetMapping("/metrics")
    @RequiresPermission({Permission.ASSESSMENTS_READ_ALL, Permission.ASSESSMENTS_READ_ORG})
    @Operation(
        summary = "Get assessment metrics",
        description = "Get assessment statistics by status and past due count",
        parameters = {
            @Parameter(name = "organizationId", description = "Filter metrics by organization ID (optional)")
        },
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Successfully retrieved metrics",
                content = @Content(schema = @Schema(implementation = AssessmentMetricsDto.class))
            ),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<JsonApiResponse<AssessmentMetricsDto>> getMetrics(
        @Parameter(hidden = true) @RequestParam(required = false) String organizationId,
        Authentication authentication
    ) {
        AssessmentMetricsDto metrics = assessmentService.getMetrics(organizationId, authentication);
        return ResponseUtil.success("Metrics retrieved successfully", metrics);
    }

    @GetMapping("/metrics/vulnerability-trend")
    @RequiresPermission({Permission.ASSESSMENTS_READ_ALL, Permission.ASSESSMENTS_READ_ORG})
    @Operation(
        summary = "Get vulnerability trend",
        description = "Daily vulnerability counts per severity for a lifecycle event type "
            + "(CREATED, CLOSED, REOPENED, SEVERITY_CHANGED), backed by a TimescaleDB continuous aggregate",
        parameters = {
            @Parameter(name = "eventType", description = "Lifecycle event to chart (default CREATED)"),
            @Parameter(name = "days", description = "Trailing window size in days (default 90)"),
            @Parameter(name = "organizationId", description = "Filter by organization ID (optional)")
        }
    )
    public ResponseEntity<JsonApiResponse<List<VulnerabilityTrendPointDto>>> getVulnerabilityTrend(
        @RequestParam(required = false) String eventType,
        @RequestParam(required = false, defaultValue = "90") int days,
        @Parameter(hidden = true) @RequestParam(required = false) String organizationId,
        Authentication authentication
    ) {
        List<VulnerabilityTrendPointDto> trend =
            assessmentService.getVulnerabilityTrend(organizationId, eventType, days, authentication);
        return ResponseUtil.success("Vulnerability trend retrieved successfully", trend);
    }

    @GetMapping("/calendar")
    @RequiresPermission({Permission.ASSESSMENTS_READ_ALL, Permission.ASSESSMENTS_READ_ORG})
    @Operation(
        summary = "Get assessments for calendar view",
        description = "Get assessments within a date range for calendar visualization",
        parameters = {
            @Parameter(name = "startDate", description = "Start date (ISO format)", example = "2024-01-01T00:00:00", required = true),
            @Parameter(name = "endDate", description = "End date (ISO format)", example = "2024-12-31T23:59:59", required = true),
            @Parameter(name = "page", description = "Page number (0-indexed)", example = "0"),
            @Parameter(name = "size", description = "Number of items per page", example = "100")
        },
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Successfully retrieved assessments",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid date format"),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<JsonApiResponse<List<AssessmentDto>>> getCalendarView(
        @Parameter(hidden = true) @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
        @Parameter(hidden = true) @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
        @Parameter(hidden = true) @RequestParam(defaultValue = "0") int page,
        @Parameter(hidden = true) @RequestParam(defaultValue = "100") int size,
        Authentication authentication
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "startDate"));
        Page<AssessmentDto> assessments = assessmentService.getAssessmentsByDateRange(startDate, endDate, pageable, authentication);
        return ResponseUtil.paginated("Assessments retrieved successfully", assessments);
    }

    @PostMapping("/check-conflicts")
    @RequiresPermission(Permission.ASSESSMENTS_CREATE_ALL)
    @Operation(
        summary = "Check for conflicting assessments",
        description = "Check if there are any assessments with overlapping dates and shared assessors",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Successfully checked for conflicts",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))
            ),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<JsonApiResponse<List<AssessmentDto>>> checkConflicts(
        @RequestBody ConflictCheckRequest request
    ) {
        List<AssessmentDto> conflicts = assessmentService.detectConflicts(
            request.getAssessmentId(),
            request.getAssessorIds(),
            request.getStartDate(),
            request.getEndDate()
        );
        return ResponseUtil.success("Conflict check completed", conflicts);
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresPermission({Permission.ASSESSMENTS_READ_ALL, Permission.ASSESSMENTS_READ_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(summary = "Subscribe to assessment events",
               description = "Opens a Server-Sent Events stream for real-time field lock/unlock events on the assessment.")
    public SseEmitter subscribeToEvents(
            @PathVariable String id,
            @RequestParam(defaultValue = "") String clientId,
            Authentication authentication) {
        assessmentService.getAssessment(id); // 404 guard
        String username = authentication.getName();
        String clientKey = username + ":" + (clientId.isEmpty() ? "default" : clientId);
        return assessmentLockService.subscribe(id, clientKey);
    }

    @PostMapping("/{id}/fields/{fieldId}/lock")
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(summary = "Acquire field lock",
               description = "Acquires an edit lock on an assessment field for collaborative editing. Returns 409 if another user holds the lock.")
    public ResponseEntity<JsonApiResponse<Void>> acquireFieldLock(
            @PathVariable String id, @PathVariable String fieldId,
            Authentication authentication) {
        String username = authentication.getName();
        String displayName = userService.findByUsername(username).map(u -> {
            String n = ((u.getFirstName() != null ? u.getFirstName() : "") + " " +
                        (u.getLastName()  != null ? u.getLastName()  : "")).trim();
            return n.isEmpty() ? username : n;
        }).orElse(username);
        if (!assessmentLockService.acquireLock(id, fieldId, username, displayName))
            return ResponseUtil.error(HttpStatus.CONFLICT, "Field is locked by another user");
        return ResponseUtil.success("Lock acquired", null);
    }

    @DeleteMapping("/{id}/fields/{fieldId}/lock")
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(summary = "Release field lock",
               description = "Releases the caller's edit lock on an assessment field.")
    public ResponseEntity<JsonApiResponse<Void>> releaseFieldLock(
            @PathVariable String id, @PathVariable String fieldId,
            Authentication authentication) {
        assessmentLockService.releaseLock(id, fieldId, authentication.getName());
        return ResponseUtil.success("Lock released", null);
    }
    @GetMapping(value = "/export/csv", produces = MediaType.TEXT_PLAIN_VALUE)
    @RequiresPermission({Permission.ASSESSMENTS_READ_ALL, Permission.ASSESSMENTS_READ_ORG})
    @Operation(
        summary = "Export assessments to CSV",
        description = "Export assessments to CSV format with same filters as search",
        parameters = {
            @Parameter(name = "applicationId", description = "Filter by application ID"),
            @Parameter(name = "organizationId", description = "Filter by organization ID"),
            @Parameter(name = "assessmentTypeId", description = "Filter by assessment type ID"),
            @Parameter(name = "assessorId", description = "Filter by assessor user ID"),
            @Parameter(name = "status", description = "Filter by status", example = "IN_PROGRESS"),
            @Parameter(name = "name", description = "Search by name (case-insensitive)")
        },
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Successfully exported assessments to CSV",
                content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE)
            ),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<String> exportToCsv(
        @Parameter(hidden = true) @RequestParam(required = false) String applicationId,
        @Parameter(hidden = true) @RequestParam(required = false) String organizationId,
        @Parameter(hidden = true) @RequestParam(required = false) String assessmentTypeId,
        @Parameter(hidden = true) @RequestParam(required = false) String assessorId,
        @Parameter(hidden = true) @RequestParam(required = false) String status,
        @Parameter(hidden = true) @RequestParam(required = false) String name,
        Authentication authentication
    ) {
        // Get all assessments with filters (no pagination for export)
        Pageable pageable = Pageable.unpaged();
        Page<AssessmentDto> assessments = assessmentService.searchAssessments(
            applicationId, organizationId, assessmentTypeId, assessorId, status, name, pageable, authentication
        );

        String csv = assessmentService.exportToCsv(assessments.getContent());

        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=assessments.csv")
            .body(csv);
    }

    // ── File attachment endpoints ────────────────────────────────────────────

    @PostMapping("/{id}/files/prepare")
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(summary = "Allocate an upload target",
               description = "Returns a file id and the backend URL to PUT the file body to. " +
                             "After the upload completes, call POST /{id}/files to confirm.")
    public ResponseEntity<JsonApiResponse<UploadTargetResponse>> prepareUpload(
            @PathVariable String id,
            @Valid @RequestBody PrepareUploadRequest request,
            Authentication authentication) {
        UploadTargetResponse response = assessmentService.prepareUpload(
                id, request.getFileName(), authentication.getName());
        return ResponseUtil.success("Upload target allocated", response);
    }

    /**
     * Receive an attachment's bytes and stream them into storage.
     *
     * <p>The body is piped straight through, so a large evidence file never
     * lands on the heap. {@code fileName} must match the one given to the
     * prepare step — it is part of the storage key.
     */
    @PutMapping("/{id}/files/{fileId}/content")
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(summary = "Upload an attachment's bytes",
               description = "Streams the request body into storage under the prepared file id.")
    public ResponseEntity<JsonApiResponse<Void>> uploadContent(
            @PathVariable String id,
            @PathVariable String fileId,
            @RequestParam String fileName,
            HttpServletRequest request) throws IOException {
        assessmentService.storeUpload(id, fileId, fileName,
                uploadRequests.contentType(request), uploadRequests.contentLength(request),
                request.getInputStream());
        return ResponseUtil.success("File uploaded", null);
    }

    @GetMapping("/{id}/files/{fileId}/content")
    @RequiresPermission({Permission.ASSESSMENTS_READ_ALL, Permission.ASSESSMENTS_READ_TEAM, Permission.ASSESSMENTS_READ_ASSIGNED, Permission.ASSESSMENTS_READ_ORG, Permission.ASSESSMENTS_READ_OWNED})
    @Operation(summary = "Download an attachment",
               description = "Streams the file's bytes as an attachment. Requires read access to the assessment.")
    public ResponseEntity<Resource> downloadContent(
            @PathVariable String id,
            @PathVariable String fileId) {
        StorageService.StoredFile file = assessmentService.openFile(id, fileId);
        return FileStreamResponse.attachment(file.stream(), file.fileName());
    }

    @PostMapping("/{id}/files")
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(summary = "Confirm file upload",
               description = "Persists file metadata after a successful direct upload to MinIO.")
    public ResponseEntity<JsonApiResponse<AssessmentFileDto>> confirmUpload(
            @PathVariable String id,
            @Valid @RequestBody ConfirmUploadRequest request,
            Authentication authentication) {
        AssessmentFileDto file = assessmentService.confirmFileUpload(
                id, request.getFileId(), request.getFileName(),
                request.getContentType(), request.getFileSize(), authentication.getName());
        return ResponseUtil.success("File confirmed", file);
    }


    @DeleteMapping("/{id}/files/{fileId}")
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(summary = "Delete a file",
               description = "Deletes the file from storage and removes its metadata from the assessment.")
    public ResponseEntity<JsonApiResponse<Void>> deleteFile(
            @PathVariable String id,
            @PathVariable String fileId,
            Authentication authentication) {
        assessmentService.deleteFile(id, fileId, authentication.getName());
        return ResponseUtil.success("File deleted", null);
    }

    // ── Report generation endpoint ───────────────────────────────────────────

    @PostMapping("/{id}/report/generate")
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(summary = "Generate assessment report",
        responses = {
            @ApiResponse(responseCode = "200", description = "Report generated successfully"),
            @ApiResponse(responseCode = "404", description = "Assessment not found"),
            @ApiResponse(responseCode = "501", description = "Not implemented in this edition"),
        })
    public ResponseEntity<JsonApiResponse<AssessmentDto>> generateReport(
            @PathVariable String id,
            Authentication authentication) {
        String userId = authentication.getName();
        AssessmentDto assessment = reportGenerationService.generateReport(id, userId);
        return ResponseUtil.success("Report generated successfully", assessment);
    }
}
