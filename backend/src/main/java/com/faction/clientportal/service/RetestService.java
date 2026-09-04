package com.faction.clientportal.service;

import com.faction.clientportal.dto.CompleteRetestRequest;
import com.faction.clientportal.dto.CreateRetestRequest;
import com.faction.clientportal.dto.RetestDto;
import com.faction.clientportal.dto.UpdateRetestRequest;
import com.faction.clientportal.dto.UpdateVulnerabilityStatusRequest;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentWorkflowConfig.RemediationStage;
import com.faction.clientportal.model.EmailNotificationEvent;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.Retest;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilityComment;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.RetestRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RetestService {

    private final RetestRepository retestRepository;
    private final AccessScopeService accessScopeService;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final AssessmentRepository assessmentRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final VulnerabilityService vulnerabilityService;
    private final AssessmentWorkflowConfigService workflowConfigService;
    private final VulnerabilityEventService vulnerabilityEventService;
    private final com.faction.clientportal.service.extension.ExtensionEventService extensionEventService;
    private final com.faction.clientportal.service.email.EventNotificationEmailSender eventEmailSender;

    /** Retest status for an app-owner request awaiting scheduling by staff. */
    public static final String RETEST_REQUESTED = "REQUESTED";

    /** Called off before it was verified. Kept on the finding's record rather than deleted. */
    public static final String RETEST_CANCELLED = "CANCELLED";

    /** Retest statuses that mean work is booked in — these require an assessor and a date window. */
    private static final Set<String> SCHEDULED_STATUSES = Set.of("SCHEDULED", "IN_PROGRESS");

    /**
     * Retest statuses that still count as live work on the finding. A finding may only carry one
     * of these at a time: a second open retest splits the verification history in two, and both
     * copies then race to write the vulnerability's status.
     */
    static final Set<String> OPEN_STATUSES = Set.of(RETEST_REQUESTED, "SCHEDULED", "IN_PROGRESS");

    static final String SCHEDULE_REQUIRES_ASSESSOR =
            "A retest cannot be scheduled without at least one assigned assessor";
    static final String SCHEDULE_REQUIRES_DATES =
            "A retest cannot be scheduled without both a start and an end date";
    static final String DUPLICATE_OPEN_RETEST =
            "A retest is already open for this vulnerability. Reschedule or cancel the existing "
            + "retest instead of creating another.";

    private static boolean isEmpty(List<String> ids) {
        return ids == null || ids.isEmpty();
    }

    /** Vulnerability status set when a retest is scheduled. */
    public static final String STATUS_IN_RETEST = "In Retest";
    /** Vulnerability status set when a retest completes with PASS. */
    public static final String STATUS_PASSED_RETEST = "Passed Retest";
    /** Vulnerability status set when a retest completes with FAIL. */
    public static final String STATUS_FAILED_RETEST = "Failed Retest";

    // ── Create ────────────────────────────────────────────────────────────────

    public RetestDto create(String assessmentId, CreateRetestRequest request, String userId) {
        return create(assessmentId, request, userId, null);
    }

    public RetestDto create(String assessmentId, CreateRetestRequest request, String userId,
                            Authentication authentication) {
        Assessment assessment = getAssessmentOrThrow(assessmentId);
        accessScopeService.checkAssessmentAccess(authentication, assessment);

        Vulnerability vuln = vulnerabilityRepository
                .findByIdAndAssessmentIdAndDeletedAtIsNull(request.getVulnerabilityId(), assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Vulnerability not found: " + request.getVulnerabilityId()));

        // No dates = a retest REQUEST (app owners can't pick assessors/dates);
        // staff schedule it later from the remediation queue.
        boolean requested = request.getScheduledStartDate() == null
                || request.getScheduledEndDate() == null;
        if (requested && (request.getScheduledStartDate() != null || request.getScheduledEndDate() != null)) {
            throw new IllegalArgumentException(
                    "Provide both scheduledStartDate and scheduledEndDate, or neither to request a retest");
        }
        if (!requested) {
            // Requesting and scheduling are different acts. An app owner asks for a retest;
            // picking who runs it and when is staff work, so a dated create is staff-only.
            denyExternalUser(userId, "schedule a retest");
            if (isEmpty(request.getAssignedAssessorIds())) {
                throw new IllegalArgumentException(SCHEDULE_REQUIRES_ASSESSOR);
            }
        }

        // One live retest per finding. Staff reach this from several places (the remediation
        // queue, the vulnerability drawer, a bulk selection in the vulnerability list), so the
        // check has to sit here rather than in any one of them.
        if (!retestRepository.findByVulnerabilityIdAndStatusInAndDeletedAtIsNull(
                vuln.getId(), OPEN_STATUSES).isEmpty()) {
            throw new IllegalArgumentException(DUPLICATE_OPEN_RETEST);
        }

        Retest retest = Retest.builder()
                .vulnerabilityId(vuln.getId())
                .assessmentId(assessmentId)
                .applicationId(assessment.getApplicationId())
                .scheduledStartDate(request.getScheduledStartDate())
                .scheduledEndDate(request.getScheduledEndDate())
                .assignedAssessorIds(request.getAssignedAssessorIds() != null
                        ? request.getAssignedAssessorIds() : new ArrayList<>())
                .scope(request.getScope())
                .comment(request.getComment())
                .status(requested ? RETEST_REQUESTED : "SCHEDULED")
                .createdBy(userId)
                .lastUpdatedBy(userId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        Retest saved = retestRepository.save(retest);

        // Append system comment on the Vulnerability
        String displayName = resolveDisplayName(userId);
        if (requested) {
            appendSystemComment(vuln, userId, displayName,
                    "**Retest requested** by " + displayName);
        } else {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            String startStr = request.getScheduledStartDate().toLocalDate().format(fmt);
            String endStr = request.getScheduledEndDate().toLocalDate().format(fmt);
            appendSystemComment(vuln, userId, displayName, "**Retest scheduled** by " + displayName
                    + " \u2014 scheduled for " + startStr + " to " + endStr);

            // Scheduling a retest moves the vulnerability into "In Retest".
            // updateStatus handles closedAt bookkeeping, the status-change system
            // comment, and analytics events. A mere request leaves the
            // vulnerability status alone until staff actually schedule it.
            setVulnerabilityStatus(assessmentId, vuln.getId(), STATUS_IN_RETEST, userId);

            // Notify assigned assessors
            notifyRetestAssessors(saved.getAssignedAssessorIds(), vuln.getName(), saved.getAssessmentId(), "RETEST_ASSIGNED");

            extensionEventService.verificationChanged(saved.getId(), userId,
                    com.faction.extender.VerificationManager.Operation.Assigned);

            emailRetestEvent(EmailNotificationEvent.RETEST_SCHEDULED, assessment, vuln, saved);
        }

        return enrich(saved);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public List<RetestDto> getByAssessment(String assessmentId) {
        return getByAssessment(assessmentId, null);
    }

    public List<RetestDto> getByAssessment(String assessmentId, Authentication authentication) {
        accessScopeService.checkAssessmentAccess(authentication, getAssessmentOrThrow(assessmentId));
        return retestRepository.findByAssessmentIdAndDeletedAtIsNull(assessmentId)
                .stream()
                .map(this::enrich)
                .collect(Collectors.toList());
    }

    public List<RetestDto> getByVulnerability(String vulnerabilityId) {
        return retestRepository.findByVulnerabilityIdAndDeletedAtIsNull(vulnerabilityId)
                .stream()
                .map(this::enrich)
                .collect(Collectors.toList());
    }

    public List<RetestDto> getAll(boolean assignedToMe, String username) {
        return getAll(assignedToMe, null, username, null);
    }

    public List<RetestDto> getAll(boolean assignedToMe, String username, Authentication authentication) {
        return getAll(assignedToMe, null, username, authentication);
    }

    /** @param status optional comma-separated status filter, e.g. "REQUESTED,SCHEDULED,IN_PROGRESS" */
    public List<RetestDto> getAll(boolean assignedToMe, String status, String username,
                                  Authentication authentication) {
        List<Retest> retests;
        if (assignedToMe) {
            String userId = userRepository.findByUsername(username)
                    .map(u -> u.getId())
                    .orElse(username);
            retests = retestRepository.findByAssignedAssessorIdsContainingAndDeletedAtIsNull(userId);
        } else {
            retests = retestRepository.findByDeletedAtIsNull();
        }
        if (status != null && !status.isBlank()) {
            java.util.Set<String> wanted = java.util.Arrays.stream(status.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(String::toUpperCase)
                    .collect(Collectors.toSet());
            retests = retests.stream()
                    .filter(r -> r.getStatus() != null && wanted.contains(r.getStatus().toUpperCase()))
                    .collect(Collectors.toList());
        }
        return filterToScope(retests, authentication).stream().map(this::enrich).collect(Collectors.toList());
    }

    public List<RetestDto> getCalendar(LocalDateTime from, LocalDateTime to) {
        return getCalendar(from, to, null);
    }

    public List<RetestDto> getCalendar(LocalDateTime from, LocalDateTime to, Authentication authentication) {
        return filterToScope(
                retestRepository.findByScheduledStartDateBetweenAndDeletedAtIsNull(from, to), authentication)
                .stream()
                .map(this::enrich)
                .collect(Collectors.toList());
    }

    public RetestDto getById(String id) {
        return getById(id, null);
    }

    public RetestDto getById(String id, Authentication authentication) {
        Retest retest = retestRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Retest not found: " + id));
        assessmentRepository.findByIdAndDeletedAtIsNull(retest.getAssessmentId())
                .ifPresent(a -> accessScopeService.checkAssessmentAccess(authentication, a));
        return enrich(retest);
    }

    // ── Update ────────────────────────────────────────────────────────────────

    public RetestDto update(String id, UpdateRetestRequest request, String userId) {
        denyExternalUser(userId, "change a retest");
        Retest retest = retestRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Retest not found: " + id));

        boolean wasRequested = RETEST_REQUESTED.equals(retest.getStatus());
        String previousStatus = retest.getStatus();
        List<String> newlyAssigned = List.of();

        if (request.getScheduledStartDate() != null) retest.setScheduledStartDate(request.getScheduledStartDate());
        if (request.getScheduledEndDate() != null) retest.setScheduledEndDate(request.getScheduledEndDate());
        if (request.getAssignedAssessorIds() != null) {
            List<String> previous = retest.getAssignedAssessorIds() != null ? retest.getAssignedAssessorIds() : List.of();
            List<String> newlyAdded = request.getAssignedAssessorIds().stream()
                    .filter(aid -> !previous.contains(aid))
                    .toList();
            retest.setAssignedAssessorIds(request.getAssignedAssessorIds());
            // Notify newly added assessors
            String vulnName = vulnerabilityRepository.findByIdAndDeletedAtIsNull(retest.getVulnerabilityId())
                    .map(v -> v.getName()).orElse("a vulnerability");
            notifyRetestAssessors(newlyAdded, vulnName, retest.getAssessmentId(), "RETEST_ASSIGNED");
            newlyAssigned = newlyAdded;
        }
        if (request.getScope() != null) retest.setScope(request.getScope());
        if (request.getComment() != null) retest.setComment(request.getComment());
        if (request.getStatus() != null) retest.setStatus(request.getStatus());

        // Staff scheduling a requested retest: once both dates are set, the
        // request becomes a scheduled retest.
        if (wasRequested && RETEST_REQUESTED.equals(retest.getStatus())
                && retest.getScheduledStartDate() != null && retest.getScheduledEndDate() != null) {
            if (isEmpty(retest.getAssignedAssessorIds())) {
                throw new IllegalArgumentException(SCHEDULE_REQUIRES_ASSESSOR);
            }
            retest.setStatus("SCHEDULED");

            vulnerabilityRepository.findByIdAndDeletedAtIsNull(retest.getVulnerabilityId())
                    .ifPresent(vuln -> {
                        String displayName = resolveDisplayName(userId);
                        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                        appendSystemComment(vuln, userId, displayName,
                                "**Retest scheduled** by " + displayName + " — scheduled for "
                                        + retest.getScheduledStartDate().toLocalDate().format(fmt)
                                        + " to " + retest.getScheduledEndDate().toLocalDate().format(fmt));
                    });
        }

        // A retest that is in flight must be actionable: somebody to do it, and a window to do it in.
        // Applied when this update *puts* it into that state, or when it touches the scheduling
        // fields — not to every edit of an already-scheduled retest. Checking the resulting state
        // unconditionally made retests that predate this rule (scheduled with no assessor, or with
        // a date missing) impossible to save at all: adding a comment would fail validation for
        // something the edit never touched.
        boolean enteringScheduled = !SCHEDULED_STATUSES.contains(previousStatus)
                && SCHEDULED_STATUSES.contains(retest.getStatus());
        boolean touchedScheduling = request.getScheduledStartDate() != null
                || request.getScheduledEndDate() != null
                || request.getAssignedAssessorIds() != null;
        if (SCHEDULED_STATUSES.contains(retest.getStatus()) && (enteringScheduled || touchedScheduling)) {
            if (isEmpty(retest.getAssignedAssessorIds())) {
                throw new IllegalArgumentException(SCHEDULE_REQUIRES_ASSESSOR);
            }
            if (retest.getScheduledStartDate() == null || retest.getScheduledEndDate() == null) {
                throw new IllegalArgumentException(SCHEDULE_REQUIRES_DATES);
            }
        }

        applyRatingChanges(retest, request.getSeverity(), request.getLikelihood(), request.getImpact(), userId);

        // Whichever way the retest's status moved — dates filled in above, or an explicit status on
        // the request — the vulnerability follows it. Without this an API caller could PATCH a retest
        // straight to SCHEDULED or PASSED and leave the vulnerability's status stale.
        applyRetestStatusToVulnerability(retest, previousStatus, userId);

        retest.setLastUpdatedBy(userId);
        retest.setUpdatedAt(LocalDateTime.now());

        Retest saved = retestRepository.save(retest);

        // The extender API has no "verification updated" operation, so only a change
        // that puts new people on the retest is worth reporting — that is the one an
        // integration acts on.
        if (!newlyAssigned.isEmpty()) {
            extensionEventService.verificationChanged(saved.getId(), userId,
                    com.faction.extender.VerificationManager.Operation.Assigned);
        }

        // Only the transition into a scheduled state is worth an email. Editing the scope
        // of an already-scheduled retest is not "a retest has been scheduled".
        if (enteringScheduled) {
            emailRetestEvent(EmailNotificationEvent.RETEST_SCHEDULED, null, null, saved);
        }

        return enrich(saved);
    }

    /**
     * Mirrors a retest status transition onto its vulnerability: SCHEDULED / IN_PROGRESS →
     * "In Retest", PASSED → "Passed Retest", FAILED → "Failed Retest". REQUESTED and CANCELLED
     * leave the vulnerability alone (a request hasn't started, and cancelling restores nothing —
     * see {@link #delete}). No-ops when the retest's status did not actually change, so editing a
     * scheduled retest's dates or comment never overwrites a status set by hand.
     */
    private void applyRetestStatusToVulnerability(Retest retest, String previousStatus, String userId) {
        if (Objects.equals(previousStatus, retest.getStatus())) {
            return;
        }
        String vulnStatus = switch (retest.getStatus() == null ? "" : retest.getStatus()) {
            case "SCHEDULED", "IN_PROGRESS" -> STATUS_IN_RETEST;
            case "PASSED" -> STATUS_PASSED_RETEST;
            case "FAILED" -> STATUS_FAILED_RETEST;
            default -> null;
        };
        if (vulnStatus != null) {
            setVulnerabilityStatus(retest.getAssessmentId(), retest.getVulnerabilityId(), vulnStatus, userId);
        }
    }

    // ── Complete ──────────────────────────────────────────────────────────────

    public RetestDto complete(String id, CompleteRetestRequest request, String userId) {
        denyExternalUser(userId, "verify a retest");
        Retest retest = retestRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Retest not found: " + id));

        String result = request.getResult().toUpperCase();
        if (!result.equals("PASS") && !result.equals("FAIL")) {
            throw new IllegalArgumentException("Result must be PASS or FAIL");
        }
        if (RETEST_REQUESTED.equals(retest.getStatus())) {
            throw new IllegalArgumentException("Retest must be scheduled before it can be completed");
        }

        retest.setResult(result);
        retest.setStatus(result.equals("PASS") ? "PASSED" : "FAILED");
        retest.setClosedDate(LocalDateTime.now());
        // Stamped once, unlike lastUpdatedBy — a later edit must not reassign the sign-off.
        // userId here is the JWT subject (a username), matching createdBy/lastUpdatedBy.
        retest.setCompletedBy(userId);
        if (request.getComment() != null) retest.setComment(request.getComment());
        retest.setLastUpdatedBy(userId);
        retest.setUpdatedAt(LocalDateTime.now());

        Retest saved = retestRepository.save(retest);

        applyRatingChanges(retest, request.getSeverity(), request.getLikelihood(), request.getImpact(), userId);

        // Append system comment to the vulnerability
        vulnerabilityRepository.findByIdAndDeletedAtIsNull(retest.getVulnerabilityId())
                .ifPresent(vuln -> {
                    String displayName = resolveDisplayName(userId);
                    StringBuilder sb = new StringBuilder("**Retest " + saved.getStatus() + "** by " + displayName);
                    if (request.getComment() != null && !request.getComment().isBlank()) {
                        sb.append(" \u2014 ").append(request.getComment());
                    }
                    appendSystemComment(vuln, userId, displayName, sb.toString());
                });

        // The retest outcome drives the vulnerability status.
        setVulnerabilityStatus(retest.getAssessmentId(), retest.getVulnerabilityId(),
                result.equals("PASS") ? STATUS_PASSED_RETEST : STATUS_FAILED_RETEST, userId);

        if (result.equals("PASS")) {
            applyClosure(retest, closureStageId(request.getClosure()), userId);
        }

        recordCompletionEvent(saved, result);

        extensionEventService.verificationChanged(saved.getId(), userId,
                result.equals("PASS") ? com.faction.extender.VerificationManager.Operation.PASS
                                      : com.faction.extender.VerificationManager.Operation.FAIL);

        emailRetestEvent(EmailNotificationEvent.RETEST_COMPLETED, null, null, saved);

        return enrich(saved);
    }

    /**
     * Applies a retest's revised ratings to the underlying vulnerability and records the change as
     * a system comment on it.
     *
     * <p>Lives here rather than going through the vulnerability API because a retest runs on a
     * <em>finalized</em> assessment by definition, and {@code VulnerabilityService.updateVulnerability}
     * refuses to modify one. The retest workflow is already the sanctioned way to change a
     * vulnerability after its assessment is closed — it is what sets "In Retest" and closes the
     * finding on a pass — so re-rating belongs on the same path.
     *
     * <p>Each argument is null when unchanged. Severity is a closed enum with no "unset"; likelihood
     * and impact are free-form and a blank value clears them.
     */
    private void applyRatingChanges(Retest retest, String severity, String likelihood, String impact,
                                    String userId) {
        if (severity == null && likelihood == null && impact == null) {
            return;
        }
        var vulnOpt = vulnerabilityRepository.findByIdAndDeletedAtIsNull(retest.getVulnerabilityId());
        if (vulnOpt.isEmpty()) {
            return;
        }
        Vulnerability vuln = vulnOpt.get();
        List<String> rows = new ArrayList<>();

        if (severity != null && !severity.isBlank()) {
            VulnerabilitySeverity parsed;
            try {
                parsed = VulnerabilitySeverity.valueOf(severity.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown severity: " + severity);
            }
            if (parsed != vuln.getSeverity()) {
                rows.add(ratingRow("Severity", label(vuln.getSeverity()), label(parsed)));
                vuln.setSeverity(parsed);
            }
        }
        if (likelihood != null && !likelihood.equals(orEmpty(vuln.getLikelihood()))) {
            rows.add(ratingRow("Likelihood", label(vuln.getLikelihood()), label(likelihood)));
            vuln.setLikelihood(likelihood.isBlank() ? null : likelihood);
        }
        if (impact != null && !impact.equals(orEmpty(vuln.getImpact()))) {
            rows.add(ratingRow("Impact", label(vuln.getImpact()), label(impact)));
            vuln.setImpact(impact.isBlank() ? null : impact);
        }
        if (rows.isEmpty()) {
            return;
        }

        vuln.setLastUpdatedBy(userId);
        vuln.setUpdatedAt(LocalDateTime.now());
        vulnerabilityRepository.save(vuln);

        // System comments render as markdown, so a markdown table is enough — no inline styling
        // needed for it to read correctly wherever comments are shown.
        String displayName = resolveDisplayName(userId);
        String table = "**Ratings revised on retest** by " + displayName + "\n\n"
                + "| Rating | Previous | New |\n| --- | --- | --- |\n"
                + String.join("\n", rows);
        appendSystemComment(vuln, userId, displayName, table);
    }

    private static String ratingRow(String name, String from, String to) {
        return "| " + name + " | " + from + " | " + to + " |";
    }

    private static String label(Object value) {
        String s = value == null ? "" : value.toString();
        return s.isBlank() ? "—" : s;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Sentinel closure value: complete the retest and touch nothing on the vulnerability. */
    public static final String CLOSURE_RETEST_ONLY = "RETEST_ONLY";

    /**
     * Resolves a request's closure value to a configured remediation stage id. Accepts a stage id
     * directly, plus the legacy enum names (DEVELOPMENT / STAGING map to the default stage ids;
     * PRODUCTION maps to whichever stage is currently terminal) so pre-existing integrations keep
     * working. Null/blank/RETEST_ONLY resolves to null: close nothing.
     */
    private String closureStageId(String value) {
        if (value == null || value.isBlank() || CLOSURE_RETEST_ONLY.equalsIgnoreCase(value.trim())) {
            return null;
        }
        String trimmed = value.trim();
        List<RemediationStage> stages = workflowConfigService.remediationStages();
        String candidate = switch (trimmed.toUpperCase()) {
            case "DEVELOPMENT" -> "development";
            case "STAGING" -> "staging";
            case "PRODUCTION" -> stages.get(stages.size() - 1).getId();
            default -> trimmed;
        };
        return stages.stream()
                .filter(s -> candidate.equals(s.getId()))
                .findFirst()
                .map(RemediationStage::getId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown closure: " + value
                        + ". Expected RETEST_ONLY or a configured remediation stage id"));
    }

    /**
     * Records how far a passing retest closes the finding, against the configured remediation
     * stages. A non-terminal stage records a {@code VulnerabilityStageCompletion} and leaves the
     * vulnerability open (the fix is confirmed there, not in production, so it stays in the
     * remediation queue); the terminal stage closes it outright via the standard status path
     * (status Closed + closedAt, comment, analytics event). A null stage id touches nothing:
     * the retest is done, the finding carries on being managed.
     */
    private void applyClosure(Retest retest, String stageId, String userId) {
        if (stageId == null) {
            return;
        }
        if (vulnerabilityRepository.findByIdAndDeletedAtIsNull(retest.getVulnerabilityId()).isEmpty()) {
            return;
        }
        vulnerabilityService.recordStageCompletion(
                retest.getAssessmentId(), retest.getVulnerabilityId(), stageId, userId,
                "confirmed by retest");
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    public void cancel(String id, String userId) {
        cancel(id, userId, null);
    }

    /**
     * Cancels a retest — the app owner who asked for it can withdraw the request, and staff can
     * call it off.
     *
     * <p>Moves it to {@link #RETEST_CANCELLED} rather than removing it. A cancellation is part of
     * the finding's history: "we asked for a retest and then called it off" is a different story
     * from "no retest was ever requested", and a soft delete tells the second one. It leaves every
     * queue and worklist anyway, because those select the open statuses — see the remediation
     * queue's retest branch and {@code RetestActivityLogService}, neither of which counts a
     * cancelled retest as outstanding work or as a verdict.
     *
     * <p>The scope check matters more here than on the staff-only paths: cancelling is open to
     * external users, whose permission is not scoped by itself. Without it, holding
     * {@code vulnerabilities:retest:owned} would cancel any retest in the system by id.
     */
    public void cancel(String id, String userId, Authentication authentication) {
        Retest retest = retestRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Retest not found: " + id));

        assessmentRepository.findByIdAndDeletedAtIsNull(retest.getAssessmentId())
                .ifPresent(a -> accessScopeService.checkAssessmentAccess(authentication, a));

        // A completed retest is part of the vulnerability's history — it can no
        // longer be cancelled.
        if ("PASSED".equals(retest.getStatus()) || "FAILED".equals(retest.getStatus())) {
            throw new IllegalArgumentException("Cannot cancel a completed retest");
        }

        // Cancelling twice is a no-op rather than a second system comment on the finding.
        if (RETEST_CANCELLED.equals(retest.getStatus())) {
            return;
        }

        extensionEventService.verificationChanged(retest.getId(), userId,
                com.faction.extender.VerificationManager.Operation.Cancel);

        retest.setStatus(RETEST_CANCELLED);
        retest.setLastUpdatedBy(userId);
        retest.setUpdatedAt(LocalDateTime.now());
        retestRepository.save(retest);

        // Record the cancellation on the vulnerability, matching the
        // schedule/complete system comments
        vulnerabilityRepository.findByIdAndDeletedAtIsNull(retest.getVulnerabilityId())
                .ifPresent(vuln -> {
                    String displayName = resolveDisplayName(userId);
                    appendSystemComment(vuln, userId, displayName,
                            "**Retest cancelled** by " + displayName);
                });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Fails the request when the caller is a customer-side account. Mirrors
     * {@code VulnerabilityService.denyExternalUser} — see that javadoc for why the rule is the
     * account flag rather than a permission, and why an unresolvable (machine) principal passes.
     *
     * <p>An external user's part in the retest lifecycle is asking for one and withdrawing it:
     * {@link #create} without dates and {@link #cancel}. Scheduling, editing and verifying are
     * staff work.
     */
    private void denyExternalUser(String username, String action) {
        boolean external = userRepository.findByUsername(username)
                .map(u -> Boolean.FALSE.equals(u.getIsInternal()))
                .orElse(false);
        if (external) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "External users cannot " + action);
        }
    }

    /** Moves the vulnerability to the given lifecycle status via the standard
     *  status-change path (system comment, closedAt bookkeeping, events). */
    private void setVulnerabilityStatus(String assessmentId, String vulnerabilityId,
                                        String status, String userId) {
        UpdateVulnerabilityStatusRequest statusRequest = new UpdateVulnerabilityStatusRequest();
        statusRequest.setStatus(status);
        vulnerabilityService.updateStatus(assessmentId, vulnerabilityId, statusRequest, userId);
    }

    /**
     * Restricts retest lists to what the caller may reach: :org users see their organization's
     * retests, :owned users see retests of applications assigned to them (directly or via their
     * organization), team-scoped internal users see their teams', and assignment-scoped ones see
     * the retests of assessments they are an assessor on — each row is kept only if
     * {@link AccessScopeService#checkAssessmentAccess} allows its assessment.
     *
     * <p>Callers with no scoping authority at all are left unfiltered, which is the long-standing
     * behavior for internal roles; only the scopes listed here narrow the list.
     */
    private List<Retest> filterToScope(List<Retest> retests, Authentication authentication) {
        if (authentication == null) return retests;
        boolean scoped = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.endsWith(":org") || a.endsWith(":owned")
                        // Team- and assignment-scoped pentesters are internal but still restricted:
                        // without these the retest list is the one place they'd see every team's work.
                        || a.equals(Permission.ASSESSMENTS_READ_TEAM.getPermission())
                        || a.equals(Permission.VULNERABILITIES_READ_TEAM.getPermission())
                        || a.equals(Permission.ASSESSMENTS_READ_ASSIGNED.getPermission())
                        || a.equals(Permission.VULNERABILITIES_READ_ASSESSMENT.getPermission()));
        boolean unrestricted = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("super_admin")
                        || a.equals(Permission.VULNERABILITIES_READ_ALL.getPermission())
                        || a.equals(Permission.ASSESSMENTS_READ_ALL.getPermission()));
        if (!scoped || unrestricted) return retests;

        java.util.Map<String, Boolean> assessmentAllowed = new java.util.HashMap<>();
        return retests.stream()
                .filter(r -> assessmentAllowed.computeIfAbsent(r.getAssessmentId(), aid ->
                        assessmentRepository.findByIdAndDeletedAtIsNull(aid)
                                .map(a -> {
                                    try {
                                        accessScopeService.checkAssessmentAccess(authentication, a);
                                        return true;
                                    } catch (org.springframework.security.access.AccessDeniedException e) {
                                        return false;
                                    }
                                })
                                .orElse(false)))
                .collect(Collectors.toList());
    }

    private Assessment getAssessmentOrThrow(String assessmentId) {
        return assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assessment not found: " + assessmentId));
    }

    private String resolveDisplayName(String username) {
        return userRepository.findByUsername(username)
                .map(u -> {
                    String name = ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                            + (u.getLastName() != null ? u.getLastName() : "")).trim();
                    return name.isEmpty() ? username : name;
                })
                .orElse(username);
    }

    private String resolveDisplayNameById(String userId) {
        return userRepository.findById(userId)
                .map(u -> {
                    String name = ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                            + (u.getLastName() != null ? u.getLastName() : "")).trim();
                    return name.isEmpty() ? u.getUsername() : name;
                })
                .orElse(userId);
    }

    private void appendSystemComment(Vulnerability vuln, String userId, String displayName, String content) {
        VulnerabilityComment systemComment = VulnerabilityComment.builder()
                .id(UUID.randomUUID().toString())
                .authorId(userId)
                .authorName(displayName)
                .content(content)
                .systemGenerated(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        if (vuln.getComments() == null) vuln.setComments(new ArrayList<>());
        vuln.getComments().add(systemComment);
        vuln.setUpdatedAt(LocalDateTime.now());
        vulnerabilityRepository.save(vuln);
    }

    /**
     * Mails the audiences an admin has switched on for a retest event.
     *
     * <p>The assessment and vulnerability are optional: the create path already has both
     * loaded, while the update and complete paths do not, so they pass null and let this
     * fetch them. Fetching is cheap next to sending mail, and duplicating the lookup at
     * three call sites is how they drift.
     */
    private void emailRetestEvent(EmailNotificationEvent event, Assessment assessment,
                                  Vulnerability vuln, Retest retest) {
        try {
            Assessment target = assessment != null ? assessment
                    : assessmentRepository.findByIdAndDeletedAtIsNull(retest.getAssessmentId()).orElse(null);
            if (target == null) return;

            Vulnerability finding = vuln != null ? vuln
                    : vulnerabilityRepository.findByIdAndDeletedAtIsNull(retest.getVulnerabilityId()).orElse(null);
            String vulnName = finding != null && finding.getName() != null
                    ? finding.getName() : "a finding";

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            List<String> lines = new ArrayList<>();
            List<String[]> details = new ArrayList<>();
            String subject;
            String title;

            if (event == EmailNotificationEvent.RETEST_SCHEDULED) {
                subject = "Retest scheduled: " + vulnName;
                title = "Retest scheduled";
                lines.add("A retest has been scheduled for \"" + vulnName + "\".");
                if (retest.getScheduledStartDate() != null) {
                    details.add(new String[]{"Scheduled start",
                            retest.getScheduledStartDate().toLocalDate().format(fmt)});
                }
                if (retest.getScheduledEndDate() != null) {
                    details.add(new String[]{"Scheduled end",
                            retest.getScheduledEndDate().toLocalDate().format(fmt)});
                }
            } else {
                boolean passed = "PASSED".equals(retest.getStatus());
                subject = "Retest " + (passed ? "passed" : "failed") + ": " + vulnName;
                title = "Retest " + (passed ? "passed" : "failed");
                lines.add("The retest of \"" + vulnName + "\" " + (passed
                        ? "passed. The finding has been closed."
                        : "failed. The finding remains open."));
                details.add(new String[]{"Result", passed ? "Passed" : "Failed"});
                if (retest.getClosedDate() != null) {
                    details.add(new String[]{"Completed", retest.getClosedDate().toLocalDate().format(fmt)});
                }
            }

            details.add(new String[]{"Finding", vulnName});
            details.add(new String[]{"Assessment", target.getName()});
            if (retest.getComment() != null && !retest.getComment().isBlank()) {
                details.add(new String[]{"Comment", retest.getComment()});
            }

            String link = finding != null
                    ? "/vulnerabilities?vuln=" + finding.getId()
                    : "/assessments/" + target.getId();

            eventEmailSender.send(com.faction.clientportal.service.email.EventNotificationEmailSender.Event.builder()
                    .key(event.key())
                    .event(event)
                    .assessment(target)
                    .vulnerability(finding)
                    .subject(subject)
                    .title(title)
                    .lines(lines)
                    .details(details)
                    .ctaLabel("View the finding")
                    .ctaLink(link)
                    .build());

        } catch (Exception e) {
            log.warn("Could not queue the {} email for retest {}: {}", event, retest.getId(), e.getMessage());
        }
    }

    private void notifyRetestAssessors(List<String> assessorIds, String vulnName, String assessmentId, String type) {
        if (assessorIds == null || assessorIds.isEmpty()) return;
        String link = "/assessments/" + assessmentId;
        for (String assessorId : assessorIds) {
            userRepository.findById(assessorId).ifPresent(user -> {
                try {
                    notificationService.send(
                            user.getUsername(),
                            "Retest assigned to you",
                            "You have been assigned to retest: " + vulnName,
                            type,
                            link
                    );
                } catch (Exception e) {
                    // Non-critical — log and continue
                }
            });
        }
    }

    /**
     * Appends the outcome to the vulnerability event stream, so "how many retests passed and
     * failed this week" is a query over an append-only series rather than a scan of mutable
     * retest rows. Severity and organization come from the finding being retested, which is
     * what makes the counts breakable down the same way every other event is.
     */
    private void recordCompletionEvent(Retest retest, String result) {
        var vuln = vulnerabilityRepository.findByIdAndDeletedAtIsNull(retest.getVulnerabilityId()).orElse(null);
        String organizationId = assessmentRepository.findByIdAndDeletedAtIsNull(retest.getAssessmentId())
                .map(a -> a.getOrganizationId()).orElse(null);
        vulnerabilityEventService.record(
                retest.getVulnerabilityId(), retest.getAssessmentId(), organizationId,
                vuln == null ? null : vuln.getSeverity(),
                result.equals("PASS") ? VulnerabilityEventService.RETEST_PASSED
                                      : VulnerabilityEventService.RETEST_FAILED,
                null);
    }

    private RetestDto enrich(Retest retest) {
        RetestDto dto = RetestDto.fromEntity(retest);
        if (retest.getCompletedBy() != null) {
            dto.setCompletedByName(resolveDisplayName(retest.getCompletedBy()));
        }

        // Enrich with vulnerability name/severity
        vulnerabilityRepository.findByIdAndDeletedAtIsNull(retest.getVulnerabilityId())
                .ifPresent(v -> {
                    dto.setVulnerabilityName(v.getName());
                    dto.setVulnerabilitySeverity(v.getSeverity() != null ? v.getSeverity().name() : null);
                });

        // Enrich with assessment name
        assessmentRepository.findByIdAndDeletedAtIsNull(retest.getAssessmentId())
                .ifPresent(a -> dto.setAssessmentName(a.getName()));

        // Enrich with assessor display names (IDs are UUIDs, not usernames)
        if (retest.getAssignedAssessorIds() != null && !retest.getAssignedAssessorIds().isEmpty()) {
            List<String> names = retest.getAssignedAssessorIds().stream()
                    .map(this::resolveDisplayNameById)
                    .collect(Collectors.toList());
            dto.setAssignedAssessorNames(names);
        }

        return dto;
    }
}
