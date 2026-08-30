package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.RetestActivitySummaryDto;
import com.faction.clientportal.dto.RetestCompletionLogDto;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.service.RetestActivityLogService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import com.faction.clientportal.util.PageableUtil;
import com.faction.clientportal.util.PageableUtil.SortField;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Read access to audit logs.
 *
 * <p>The AI request log lives on the same {@code /logs} path but in
 * {@code AiRequestLogController}, because it is the one log type that is a paid feature —
 * keeping it here would mean the core could not be built without the enterprise module.
 */
@RestController
@RequestMapping("/api/v1/admin/logs")
@RequiredArgsConstructor
@Tag(name = "Audit Logs", description = "Audit logs of user actions")
public class AuditLogController {

    private final RetestActivityLogService retestActivityLogService;

    // ── Retest completions ────────────────────────────────────────────────────

    /** Columns the retest activity table can order by → the matching {@code Retest} property. */
    private static final Map<String, SortField> RETEST_SORTABLE_FIELDS = Map.of(
            "completedAt", SortField.value("closedDate"),
            "status", SortField.text("status"),
            "result", SortField.text("result"));

    private static final Sort RETEST_DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "closedDate");

    @GetMapping("/retests")
    @RequiresPermission(Permission.AUDIT_LOGS_READ)
    @Operation(summary = "List retest completions",
            description = "Retests verified in the given window — what was retested, the verdict, and who "
                    + "signed off. `from` and `to` are ISO dates (inclusive); they default to the last 7 "
                    + "days, which is the \"what did we complete this week\" case. `result` narrows to "
                    + "PASS or FAIL. Cancelled retests are not completions and never appear.")
    public ResponseEntity<JsonApiResponse<List<RetestCompletionLogDto>>> getRetestLog(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String result) {
        Pageable pageable = PageableUtil.of(page, Math.min(size, 100), sort,
                RETEST_DEFAULT_SORT, RETEST_SORTABLE_FIELDS);
        Page<RetestCompletionLogDto> log = retestActivityLogService.list(
                startOf(from, LocalDate.now().minusDays(6)), endOf(to, LocalDate.now()), result, pageable);
        return ResponseUtil.paginated("Retest completions retrieved", log);
    }

    @GetMapping("/retests/summary")
    @RequiresPermission(Permission.AUDIT_LOGS_READ)
    @Operation(summary = "Retest pass/fail totals",
            description = "How many retests passed and failed in the given window — the same window and "
                    + "defaults as the retest completion log, counted in the database rather than from a page.")
    public ResponseEntity<JsonApiResponse<RetestActivitySummaryDto>> getRetestSummary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseUtil.success("Retest totals retrieved", retestActivityLogService.summary(
                startOf(from, LocalDate.now().minusDays(6)), endOf(to, LocalDate.now())));
    }

    /** Start of the given ISO date, or of {@code fallback} when it is absent or unparseable. */
    private static LocalDateTime startOf(String date, LocalDate fallback) {
        return parse(date, fallback).atStartOfDay();
    }

    /** End of the given ISO date — inclusive, so a same-day from/to covers that whole day. */
    private static LocalDateTime endOf(String date, LocalDate fallback) {
        return parse(date, fallback).atTime(java.time.LocalTime.MAX);
    }

    private static LocalDate parse(String date, LocalDate fallback) {
        if (date == null || date.isBlank()) return fallback;
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            // A malformed date falls back to the default window rather than 400-ing a read-only
            // report — the window is shown on screen, so a wrong one is visible, not silent.
            return fallback;
        }
    }
}
