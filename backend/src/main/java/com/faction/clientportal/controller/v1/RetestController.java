package com.faction.clientportal.controller.v1;

import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.dto.CompleteRetestRequest;
import com.faction.clientportal.dto.CreateRetestRequest;
import com.faction.clientportal.dto.RetestDto;
import com.faction.clientportal.dto.UpdateRetestRequest;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.RetestService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Retests", description = "Retest remediation workflow endpoints")
@SecurityRequirement(name = "bearerAuth")
public class RetestController {

    private final RetestService service;

    // ── Assessment-scoped endpoints ────────────────────────────────────────────

    @PostMapping("/api/v1/assessments/{assessmentId}/retests")
    @RequiresPermission({Permission.VULNERABILITIES_CREATE_ALL, Permission.VULNERABILITIES_CREATE_TEAM, Permission.VULNERABILITIES_CREATE_ASSESSMENT, Permission.VULNERABILITIES_RETEST_ORG, Permission.VULNERABILITIES_RETEST_OWNED})
    @Operation(summary = "Create a retest for a vulnerability in an assessment")
    public ResponseEntity<JsonApiResponse<RetestDto>> create(
            @PathVariable String assessmentId,
            @Valid @RequestBody CreateRetestRequest request,
            Authentication authentication) {
        return ResponseUtil.created("Retest scheduled successfully",
                service.create(assessmentId, request, authentication.getName(), authentication));
    }

    @GetMapping("/api/v1/assessments/{assessmentId}/retests")
    @RequiresPermission({Permission.VULNERABILITIES_READ_ALL, Permission.VULNERABILITIES_READ_TEAM, Permission.VULNERABILITIES_READ_ASSESSMENT, Permission.VULNERABILITIES_READ_ORG, Permission.VULNERABILITIES_RETEST_ORG, Permission.VULNERABILITIES_READ_OWNED, Permission.VULNERABILITIES_RETEST_OWNED})
    @Operation(summary = "List retests for an assessment")
    public ResponseEntity<JsonApiResponse<List<RetestDto>>> getByAssessment(
            @PathVariable String assessmentId,
            Authentication authentication) {
        return ResponseUtil.success("Retests retrieved successfully",
                service.getByAssessment(assessmentId, authentication));
    }

    // ── Cross-cutting endpoints ────────────────────────────────────────────────

    @GetMapping("/api/v1/retests")
    @RequiresPermission({Permission.VULNERABILITIES_READ_ALL, Permission.VULNERABILITIES_READ_TEAM, Permission.VULNERABILITIES_READ_ASSESSMENT, Permission.VULNERABILITIES_READ_ORG, Permission.VULNERABILITIES_RETEST_ORG, Permission.VULNERABILITIES_READ_OWNED, Permission.VULNERABILITIES_RETEST_OWNED})
    @Operation(summary = "List all retests, optionally filtered to those assigned to the current user "
            + "and/or by status (comma-separated, e.g. REQUESTED,SCHEDULED,IN_PROGRESS)")
    public ResponseEntity<JsonApiResponse<List<RetestDto>>> getAll(
            @RequestParam(defaultValue = "false") boolean assignedToMe,
            @RequestParam(required = false) String status,
            Authentication authentication) {
        return ResponseUtil.success("Retests retrieved successfully",
                service.getAll(assignedToMe, status, authentication.getName(), authentication));
    }

    @GetMapping("/api/v1/retests/calendar")
    @RequiresPermission({Permission.VULNERABILITIES_READ_ALL, Permission.VULNERABILITIES_READ_TEAM, Permission.VULNERABILITIES_READ_ASSESSMENT, Permission.VULNERABILITIES_READ_ORG, Permission.VULNERABILITIES_RETEST_ORG, Permission.VULNERABILITIES_READ_OWNED, Permission.VULNERABILITIES_RETEST_OWNED})
    @Operation(summary = "List retests within a date range for calendar display")
    public ResponseEntity<JsonApiResponse<List<RetestDto>>> getCalendar(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            Authentication authentication) {
        return ResponseUtil.success("Retests retrieved successfully",
                service.getCalendar(startDate, endDate, authentication));
    }

    // ── Single retest endpoints ────────────────────────────────────────────────

    @GetMapping("/api/v1/retests/{id}")
    @RequiresPermission({Permission.VULNERABILITIES_READ_ALL, Permission.VULNERABILITIES_READ_TEAM, Permission.VULNERABILITIES_READ_ASSESSMENT, Permission.VULNERABILITIES_READ_ORG, Permission.VULNERABILITIES_RETEST_ORG, Permission.VULNERABILITIES_READ_OWNED, Permission.VULNERABILITIES_RETEST_OWNED})
    @Operation(summary = "Get a single retest by ID")
    public ResponseEntity<JsonApiResponse<RetestDto>> getById(@PathVariable String id,
            Authentication authentication) {
        return ResponseUtil.success("Retest retrieved successfully", service.getById(id, authentication));
    }

    @PatchMapping("/api/v1/retests/{id}")
    @RequiresPermission({Permission.VULNERABILITIES_EDIT_ALL, Permission.VULNERABILITIES_EDIT_TEAM, Permission.VULNERABILITIES_EDIT_ASSESSMENT})
    @Operation(summary = "Partially update a retest")
    public ResponseEntity<JsonApiResponse<RetestDto>> update(
            @PathVariable String id,
            @Valid @RequestBody UpdateRetestRequest request,
            Authentication authentication) {
        return ResponseUtil.success("Retest updated successfully",
                service.update(id, request, authentication.getName()));
    }

    @PostMapping("/api/v1/retests/{id}/complete")
    @RequiresPermission({Permission.VULNERABILITIES_EDIT_ALL, Permission.VULNERABILITIES_EDIT_TEAM, Permission.VULNERABILITIES_EDIT_ASSESSMENT})
    @Operation(summary = "Complete a retest with a PASS or FAIL result")
    public ResponseEntity<JsonApiResponse<RetestDto>> complete(
            @PathVariable String id,
            @Valid @RequestBody CompleteRetestRequest request,
            Authentication authentication) {
        return ResponseUtil.success("Retest completed successfully",
                service.complete(id, request, authentication.getName()));
    }

    @DeleteMapping("/api/v1/retests/{id}")
    @RequiresPermission({Permission.VULNERABILITIES_DELETE_ALL, Permission.VULNERABILITIES_DELETE_TEAM,
            Permission.VULNERABILITIES_DELETE_ASSESSMENT,
            // Cancelling is not deleting someone else's work: whoever may ask for a retest may
            // also call it off. Row-level scope is enforced in the service, since these two
            // permissions do not carry it themselves.
            Permission.VULNERABILITIES_RETEST_ORG, Permission.VULNERABILITIES_RETEST_OWNED})
    @Operation(summary = "Cancel a retest",
            description = "Moves the retest to CANCELLED and records the cancellation on the "
                    + "vulnerability — it stays on the finding's record rather than being removed, "
                    + "and drops out of the remediation queue because that shows open retests only. "
                    + "Available to staff and to the app owners who can request a retest, limited to "
                    + "the assessments the caller may read. A completed retest is history and cannot "
                    + "be cancelled.")
    public ResponseEntity<JsonApiResponse<Void>> cancel(
            @PathVariable String id,
            Authentication authentication) {
        service.cancel(id, authentication.getName(), authentication);
        return ResponseUtil.success("Retest cancelled successfully", null);
    }
}
