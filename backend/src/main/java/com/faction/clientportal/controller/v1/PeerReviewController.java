package com.faction.clientportal.controller.v1;

import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.dto.AcceptPeerReviewRequest;
import com.faction.clientportal.dto.PeerReviewDto;
import com.faction.clientportal.dto.UpdatePeerReviewRequest;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.PeerReviewLockService;
import com.faction.clientportal.service.PeerReviewService;
import com.faction.clientportal.service.UserService;
import com.faction.clientportal.util.PageableUtil;
import com.faction.clientportal.util.PageableUtil.SortField;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Peer Reviews", description = "Peer review workflow endpoints")
@SecurityRequirement(name = "bearerAuth")
public class PeerReviewController {

    private final PeerReviewService service;
    private final PeerReviewLockService lockService;
    private final UserService userService;

    /**
     * Queue columns → the sort key {@code PeerReviewService.getQueue} resolves. The three
     * {@code *Name} keys are display values enriched after the fetch, so the service orders those
     * in memory; the rest are entity properties sorted by the database.
     */
    private static final Map<String, SortField> SORTABLE_FIELDS = Map.of(
            "assessmentName", SortField.text("assessmentName"),
            "submittedByName", SortField.text("submittedByName"),
            "reviewedByName", SortField.text("reviewedByName"),
            "createdAt", SortField.value("createdAt"),
            "completedAt", SortField.value("completedAt"),
            "status", SortField.value("status"));

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    // ── Queue ──────────────────────────────────────────────────────────────────

    @GetMapping("/peer-reviews/queue")
    @RequiresPermission({Permission.PEERREVIEW_READ_ALL, Permission.PEERREVIEW_READ_TEAM})
    @Operation(summary = "Get paginated peer review queue (PENDING + IN_REVIEW)")
    public ResponseEntity<JsonApiResponse<List<PeerReviewDto>>> getQueue(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            Authentication authentication) {
        Pageable pageable = PageableUtil.of(page, size, sort, DEFAULT_SORT, SORTABLE_FIELDS);
        Page<PeerReviewDto> result = service.getQueue(pageable, authentication);
        return ResponseUtil.paginated("Peer review queue retrieved", result);
    }

    // ── Single review ──────────────────────────────────────────────────────────

    @GetMapping("/peer-reviews/{reviewId}")
    @RequiresPermission({Permission.PEERREVIEW_READ_ALL, Permission.PEERREVIEW_READ_TEAM})
    @Operation(summary = "Get a single peer review by ID")
    public ResponseEntity<JsonApiResponse<PeerReviewDto>> getById(
            @PathVariable String reviewId,
            Authentication authentication) {
        return ResponseUtil.success("Peer review retrieved", service.getPeerReview(reviewId, authentication));
    }

    @PutMapping("/peer-reviews/{reviewId}")
    @RequiresPermission({Permission.PEERREVIEW_EDIT_ALL, Permission.PEERREVIEW_EDIT_TEAM})
    @Operation(summary = "Save reviewer edits and notes")
    public ResponseEntity<JsonApiResponse<PeerReviewDto>> update(
            @PathVariable String reviewId,
            @RequestBody UpdatePeerReviewRequest request,
            Authentication authentication) {
        return ResponseUtil.success("Peer review updated",
                service.updateReview(reviewId, request, authentication.getName(), authentication));
    }

    @PostMapping("/peer-reviews/{reviewId}/start")
    @RequiresPermission({Permission.PEERREVIEW_EDIT_ALL, Permission.PEERREVIEW_EDIT_TEAM})
    @Operation(summary = "Reviewer claims the review")
    public ResponseEntity<JsonApiResponse<PeerReviewDto>> start(
            @PathVariable String reviewId,
            Authentication authentication) {
        return ResponseUtil.success("Peer review started",
                service.startReview(reviewId, authentication.getName(), authentication));
    }

    @PostMapping("/peer-reviews/{reviewId}/complete")
    @RequiresPermission({Permission.PEERREVIEW_EDIT_ALL, Permission.PEERREVIEW_EDIT_TEAM})
    @Operation(summary = "Reviewer marks review as done")
    public ResponseEntity<JsonApiResponse<PeerReviewDto>> complete(
            @PathVariable String reviewId,
            Authentication authentication) {
        return ResponseUtil.success("Peer review completed",
                service.completeReview(reviewId, authentication.getName(), authentication));
    }

    @PostMapping("/peer-reviews/{reviewId}/accept")
    @RequiresPermission({Permission.PEERREVIEW_EDIT_ALL, Permission.PEERREVIEW_EDIT_TEAM})
    @Operation(summary = "Assessor accepts selected changes and closes the review")
    public ResponseEntity<JsonApiResponse<PeerReviewDto>> accept(
            @PathVariable String reviewId,
            @RequestBody AcceptPeerReviewRequest request,
            Authentication authentication) {
        return ResponseUtil.success("Changes accepted",
                service.acceptChanges(reviewId, request, authentication.getName(), authentication));
    }

    @PostMapping("/peer-reviews/{reviewId}/reject")
    @RequiresPermission({Permission.PEERREVIEW_EDIT_ALL, Permission.PEERREVIEW_EDIT_TEAM})
    @Operation(summary = "Assessor rejects the entire review and unlocks the assessment")
    public ResponseEntity<JsonApiResponse<PeerReviewDto>> reject(
            @PathVariable String reviewId,
            Authentication authentication) {
        return ResponseUtil.success("Peer review rejected",
                service.rejectReview(reviewId, authentication.getName(), authentication));
    }

    // ── Collaborative editing ──────────────────────────────────────────────────

    @GetMapping(value = "/peer-reviews/{reviewId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresPermission({Permission.PEERREVIEW_READ_ALL, Permission.PEERREVIEW_READ_TEAM})
    @Operation(summary = "Subscribe to peer review events",
               description = "Opens a Server-Sent Events stream carrying per-editor lock/unlock "
                       + "events for the review, so two reviewers working it at once each see "
                       + "which regions the other is in.")
    public SseEmitter subscribeToEvents(
            @PathVariable String reviewId,
            @RequestParam(defaultValue = "") String clientId,
            Authentication authentication) {
        service.getPeerReview(reviewId, authentication); // 404 / scope guard
        String username = authentication.getName();
        String clientKey = username + ":" + (clientId.isEmpty() ? "default" : clientId);
        return lockService.subscribe(reviewId, clientKey);
    }

    @PostMapping("/peer-reviews/{reviewId}/fields/{fieldId}/lock")
    @RequiresPermission({Permission.PEERREVIEW_EDIT_ALL, Permission.PEERREVIEW_EDIT_TEAM})
    @Operation(summary = "Acquire an editor lock",
               description = "Takes or refreshes the caller's lock on one editable region of the "
                       + "review. Returns 409 when another active user holds it. The region id is "
                       + "an opaque client-built key, so the lock is exactly as narrow as the "
                       + "editor being typed into.")
    public ResponseEntity<JsonApiResponse<Void>> acquireFieldLock(
            @PathVariable String reviewId, @PathVariable String fieldId,
            Authentication authentication) {
        service.checkEditAccess(reviewId, authentication);
        String username = authentication.getName();
        String displayName = userService.findByUsername(username).map(u -> {
            String n = ((u.getFirstName() != null ? u.getFirstName() : "") + " " +
                        (u.getLastName()  != null ? u.getLastName()  : "")).trim();
            return n.isEmpty() ? username : n;
        }).orElse(username);
        if (!lockService.acquireLock(reviewId, fieldId, username, displayName))
            return ResponseUtil.error(HttpStatus.CONFLICT, "This section is being edited by another user");
        return ResponseUtil.success("Lock acquired", null);
    }

    @DeleteMapping("/peer-reviews/{reviewId}/fields/{fieldId}/lock")
    @RequiresPermission({Permission.PEERREVIEW_EDIT_ALL, Permission.PEERREVIEW_EDIT_TEAM})
    @Operation(summary = "Release an editor lock")
    public ResponseEntity<JsonApiResponse<Void>> releaseFieldLock(
            @PathVariable String reviewId, @PathVariable String fieldId,
            Authentication authentication) {
        service.checkEditAccess(reviewId, authentication);
        lockService.releaseLock(reviewId, fieldId, authentication.getName());
        return ResponseUtil.success("Lock released", null);
    }

    // ── Assessment-scoped ──────────────────────────────────────────────────────

    @GetMapping("/assessments/{assessmentId}/peer-reviews")
    @RequiresPermission({Permission.PEERREVIEW_READ_ALL, Permission.PEERREVIEW_READ_TEAM})
    @Operation(summary = "Get peer review history for an assessment")
    public ResponseEntity<JsonApiResponse<List<PeerReviewDto>>> getByAssessment(
            @PathVariable String assessmentId,
            Authentication authentication) {
        return ResponseUtil.success("Peer reviews retrieved",
                service.getByAssessment(assessmentId, authentication));
    }

    @PostMapping("/assessments/{assessmentId}/peer-reviews/submit")
    @RequiresPermission({Permission.PEERREVIEW_CREATE_ALL, Permission.PEERREVIEW_CREATE_ASSESSMENT})
    @Operation(summary = "Submit an assessment for peer review")
    public ResponseEntity<JsonApiResponse<PeerReviewDto>> submit(
            @PathVariable String assessmentId,
            Authentication authentication) {
        return ResponseUtil.created("Assessment submitted for peer review",
                service.submitForPeerReview(assessmentId, authentication.getName(), authentication));
    }
}
