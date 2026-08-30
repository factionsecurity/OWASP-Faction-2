package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.RemediationRowDto;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.service.RemediationQueueService;
import com.faction.clientportal.util.PageableUtil;
import com.faction.clientportal.util.PageableUtil.SortField;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/remediation")
@RequiredArgsConstructor
@Tag(name = "Remediation", description = "Remediation queue endpoints")
@SecurityRequirement(name = "bearerAuth")
public class RemediationController {

    private final RemediationQueueService remediationQueueService;

    /**
     * Columns the queue table can order by, mapped onto the keys
     * {@code VulnerabilityRepositoryImpl.orderByRemediation} understands. The "Last Retest" column
     * is absent because it is resolved in Java after the page is fetched, not in the union query.
     */
    private static final Map<String, SortField> SORTABLE_FIELDS = Map.of(
            "name", SortField.text("name"),
            "applicationName", SortField.text("applicationName"),
            "organizationName", SortField.text("organizationName"),
            "rowType", SortField.text("rowType"),
            "severity", SortField.value("severity"),
            "vulnerabilityStatus", SortField.text("vulnerabilityStatus"),
            "startDate", SortField.value("startDate"),
            "endDate", SortField.value("endDate"),
            "dueDate", SortField.value("dueDate"));

    @GetMapping("/queue-count")
    @RequiresPermission({Permission.VULNERABILITIES_READ_ALL, Permission.VULNERABILITIES_READ_TEAM})
    @Operation(summary = "Get the number of items in the remediation queue",
            description = "Counts open tracked vulnerabilities at or past their SLA warning threshold "
                    + "plus requested/scheduled/in-progress retests — the rows the remediation queue shows.")
    public ResponseEntity<JsonApiResponse<Long>> getQueueCount() {
        return ResponseUtil.success("Remediation queue count retrieved successfully",
                remediationQueueService.queueCount());
    }

    @GetMapping(value = "/export.csv", produces = MediaType.TEXT_PLAIN_VALUE)
    @RequiresPermission({
            Permission.VULNERABILITIES_READ_ALL,
            Permission.VULNERABILITIES_READ_TEAM,
            Permission.VULNERABILITIES_READ_ORG,
            Permission.VULNERABILITIES_READ_OWNED
    })
    @Operation(summary = "Export the remediation queue to CSV",
            description = "The same scoped, filtered, sorted rows as `GET /remediation/queue`, unpaginated, "
                    + "as CSV. Accepts the identical filters — `search`, `severity`, `organizationId`, "
                    + "`applicationId`, `assessmentId`, `statuses`, `type`, `includeCompletedRetests` — plus "
                    + "`sort`. Retest rows carry three extra columns the table has no room for: completed "
                    + "date, result, and who verified it, for reporting on retests completed over a period. "
                    + "Those are only populated for verified retests, which `includeCompletedRetests=true` "
                    + "brings into the result set.")
    public ResponseEntity<String> exportCsv(
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String organizationId,
            @RequestParam(required = false) String applicationId,
            @RequestParam(required = false) String assessmentId,
            @RequestParam(required = false) List<String> statuses,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "false") boolean includeCompletedRetests,
            Authentication authentication) {
        // Reuse the queue's sort whitelist so an unknown key can't reach the query.
        Sort resolved = PageableUtil.of(0, 1, sort, Sort.unsorted(), SORTABLE_FIELDS).getSort();
        String csv = remediationQueueService.exportCsv(search, severity, organizationId, applicationId,
                assessmentId, statuses, type, includeCompletedRetests, resolved, authentication);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=remediation-queue.csv")
                .body(csv);
    }

    // All four vulnerability read scopes are accepted; the service applies the caller's row-level scope,
    // so org/owned users see only their slice (matching the access they had via the old per-assessment loads).
    @GetMapping("/queue")
    @RequiresPermission({
            Permission.VULNERABILITIES_READ_ALL,
            Permission.VULNERABILITIES_READ_TEAM,
            Permission.VULNERABILITIES_READ_ORG,
            Permission.VULNERABILITIES_READ_OWNED
    })
    @Operation(summary = "List the remediation queue",
            description = "Returns a page of items requiring remediation: open tracked vulnerabilities at or "
                    + "past their SLA warning threshold, interleaved with requested, scheduled, and in-progress "
                    + "retests. Items are ordered past-due (urgent) first, then approaching-deadline (warning), "
                    + "then not-yet-due; within each group by due date — a vulnerability's SLA deadline or a "
                    + "retest's scheduled end date. Results are limited to the vulnerabilities and retests the "
                    + "caller is authorized to read. Use `page` and `size` to paginate, `search` to match on "
                    + "vulnerability or retest name, application, organization, or item type, and the "
                    + "`severity` / `organizationId` / `applicationId` / `assessmentId` / `statuses` filters "
                    + "(the same set the vulnerabilities list offers) to narrow the queue further. "
                    + "`statuses` matches the vulnerability's status — on a retest row, the status of the "
                    + "vulnerability being retested — with a null status matching `None`. `type` narrows to "
                    + "one half of the queue: `VULNERABILITY` or `RETEST`. `includeCompletedRetests` "
                    + "also shows retests that have already been verified (PASSED / FAILED) — off by "
                    + "default, since the queue is a worklist. `sort` (`field,asc|desc`) "
                    + "overrides the default tier ordering with a single column.")
    public ResponseEntity<JsonApiResponse<List<RemediationRowDto>>> getQueue(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String organizationId,
            @RequestParam(required = false) String applicationId,
            @RequestParam(required = false) String assessmentId,
            @RequestParam(required = false) List<String> statuses,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "false") boolean includeCompletedRetests,
            Authentication authentication) {
        // Absent sort keeps the queue's canonical order (tier, then due date), which the query owns.
        Pageable pageable = PageableUtil.of(page, size, sort, Sort.unsorted(), SORTABLE_FIELDS);
        Page<RemediationRowDto> result = remediationQueueService.list(
                search, severity, organizationId, applicationId, assessmentId, statuses, type,
                includeCompletedRetests, pageable, authentication);
        return ResponseUtil.paginated("Remediation queue retrieved successfully", result);
    }
}
