package com.faction.clientportal.service;

import com.faction.clientportal.dto.AcceptPeerReviewRequest;
import com.faction.clientportal.dto.PeerReviewDto;
import com.faction.clientportal.dto.PeerReviewVulnerabilityDto;
import com.faction.clientportal.dto.UpdatePeerReviewRequest;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.AssessmentPeerReviewStatus;
import com.faction.clientportal.model.PeerReview;
import com.faction.clientportal.model.PeerReviewStatus;
import com.faction.clientportal.model.PeerReviewVulnerability;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.PeerReviewRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PeerReviewService {

    private final PeerReviewRepository peerReviewRepository;
    private final AssessmentRepository assessmentRepository;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final UserRepository userRepository;
    private final AssessmentWorkflowConfigService workflowConfigService;
    private final PeerReviewLockService lockService;
    private final com.faction.clientportal.service.extension.ExtensionEventService extensionEventService;

    // ── Team scoping ──────────────────────────────────────────────────────────
    //
    // An assessment has no team of its own; it belongs to every team its
    // assessors belong to (assessorIds -> users -> teamIds). A caller holding
    // only the ":team" scope sees and works on reviews whose assessment shares
    // at least one team with them. The ":all" scope (and super_admin) is
    // unrestricted. Passing a null Authentication skips these checks — that is
    // the internal/no-actor path used by unit tests and lifecycle callers.

    private static final String SUPER_ADMIN = "super_admin";

    private boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(authority));
    }

    private Optional<User> resolveUser(Authentication authentication) {
        if (authentication == null) return Optional.empty();
        return userRepository.findByUsername(authentication.getName());
    }

    /** Teams an assessment belongs to, derived from the teams of its assessors. */
    private Set<String> assessmentTeamIds(Assessment assessment) {
        List<String> assessorIds = assessment.getAssessorIds();
        if (assessorIds == null || assessorIds.isEmpty()) return Set.of();
        return userRepository.findAllById(assessorIds).stream()
                .map(User::getTeamIds)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .collect(Collectors.toSet());
    }

    private boolean sharesTeam(User caller, Assessment assessment) {
        List<String> callerTeams = caller.getTeamIds();
        if (callerTeams == null || callerTeams.isEmpty()) return false;
        Set<String> assessmentTeams = assessmentTeamIds(assessment);
        return callerTeams.stream().anyMatch(assessmentTeams::contains);
    }

    /** True when the caller may see this assessment's reviews. */
    private boolean canRead(Authentication authentication, Assessment assessment) {
        if (authentication == null) return true;
        if (hasAuthority(authentication, SUPER_ADMIN)
                || hasAuthority(authentication, Permission.PEERREVIEW_READ_ALL.getPermission())) {
            return true;
        }
        if (hasAuthority(authentication, Permission.PEERREVIEW_READ_TEAM.getPermission())) {
            return resolveUser(authentication).map(u -> sharesTeam(u, assessment)).orElse(false);
        }
        return false;
    }

    /** Reads hide what the caller can't see rather than advertising it exists. */
    private void checkReadAccess(Authentication authentication, Assessment assessment) {
        if (!canRead(authentication, assessment)) {
            throw new ResourceNotFoundException("Peer review not found for assessment: " + assessment.getId());
        }
    }

    private void checkEditAccess(Authentication authentication, Assessment assessment) {
        if (authentication == null) return;
        if (hasAuthority(authentication, SUPER_ADMIN)
                || hasAuthority(authentication, Permission.PEERREVIEW_EDIT_ALL.getPermission())) {
            return;
        }
        if (hasAuthority(authentication, Permission.PEERREVIEW_EDIT_TEAM.getPermission())
                && resolveUser(authentication).map(u -> sharesTeam(u, assessment)).orElse(false)) {
            return;
        }
        throw new AccessDeniedException("Access denied");
    }

    /**
     * Edit guard for callers holding only a review id — the lock endpoints, which authorize the
     * act of editing a region without going through any of the mutating service methods.
     */
    public void checkEditAccess(String reviewId, Authentication authentication) {
        PeerReview review = getPeerReviewOrThrow(reviewId);
        checkEditAccess(authentication, getAssessmentOrThrow(review.getAssessmentId()));
    }

    /**
     * Submitting requires the ":all" scope, or — with the ":assessment" scope —
     * being one of the assessment's own assessors.
     */
    private void checkCreateAccess(Authentication authentication, Assessment assessment) {
        if (authentication == null) return;
        if (hasAuthority(authentication, SUPER_ADMIN)
                || hasAuthority(authentication, Permission.PEERREVIEW_CREATE_ALL.getPermission())) {
            return;
        }
        if (hasAuthority(authentication, Permission.PEERREVIEW_CREATE_ASSESSMENT.getPermission())) {
            boolean isAssessor = resolveUser(authentication)
                    .map(u -> assessment.getAssessorIds() != null
                            && assessment.getAssessorIds().contains(u.getId()))
                    .orElse(false);
            if (isAssessor) return;
        }
        throw new AccessDeniedException("Access denied");
    }

    /**
     * Reviewing your own submission defeats the purpose of peer review, so it is
     * blocked unless an admin enables it in Assessment Config. Applies to the
     * reviewing actions only — accepting/rejecting the result is the submitter's
     * own job. super_admin is exempt.
     */
    private void checkNotSelfReview(Authentication authentication, PeerReview review) {
        if (authentication == null || hasAuthority(authentication, SUPER_ADMIN)) return;
        if (workflowConfigService.getConfig().isAllowSelfPeerReview()) return;
        boolean isSubmitter = resolveUser(authentication)
                .map(u -> u.getId().equals(review.getSubmittedByUserId()))
                .orElse(false);
        if (isSubmitter) {
            throw new AccessDeniedException(
                    "You cannot peer review an assessment you submitted. "
                            + "An administrator can allow this in Assessment Config.");
        }
    }

    /**
     * Add someone to the review's reviewer list, first-contribution order, no duplicates.
     *
     * <p>Older reviews predate the list, so the claimer is folded in first — otherwise the person
     * whose name has always shown against the review would vanish the moment someone else saved.
     */
    private void recordReviewer(PeerReview review, String reviewerId) {
        if (reviewerId == null) return;
        List<String> reviewers = review.getReviewerUserIds() == null
                ? new ArrayList<>() : new ArrayList<>(review.getReviewerUserIds());
        if (reviewers.isEmpty() && review.getReviewedByUserId() != null) {
            reviewers.add(review.getReviewedByUserId());
        }
        if (!reviewers.contains(reviewerId)) reviewers.add(reviewerId);
        review.setReviewerUserIds(reviewers);
    }

    /** The acting user's id — resolved from the authenticated principal when present. */
    private String actorId(String userId, Authentication authentication) {
        return resolveUser(authentication).map(User::getId).orElse(userId);
    }

    /**
     * Submit an assessment for peer review.
     * Snapshots the full assessment state (field values + all vulnerabilities) into a PeerReview document.
     * Locks the assessment from editing.
     */
    public PeerReviewDto submitForPeerReview(String assessmentId, String userId) {
        return submitForPeerReview(assessmentId, userId, null);
    }

    public PeerReviewDto submitForPeerReview(String assessmentId, String userId, Authentication authentication) {
        Assessment assessment = getAssessmentOrThrow(assessmentId);
        checkCreateAccess(authentication, assessment);

        AssessmentPeerReviewStatus currentPrStatus = assessment.getPeerReviewStatus();
        if (currentPrStatus == AssessmentPeerReviewStatus.IN_PEER_REVIEW
                || currentPrStatus == AssessmentPeerReviewStatus.NEEDS_ACCEPTANCE) {
            throw new IllegalStateException("Assessment is already locked for peer review");
        }

        // Snapshot all live vulnerabilities
        List<Vulnerability> vulns = vulnerabilityRepository.findByAssessmentIdAndDeletedAtIsNull(assessmentId);
        List<PeerReviewVulnerability> snapshotVulns = vulns.stream()
                .map(v -> PeerReviewVulnerability.builder()
                        .vulnerabilityId(v.getId())
                        .name(v.getName())
                        .severity(v.getSeverity())
                        .order(v.getOrder())
                        .likelihood(v.getLikelihood())
                        .impact(v.getImpact())
                        .cvssScore(v.getCvssScore())
                        .cvssString(v.getCvssString())
                        .description(v.getDescription())
                        .recommendation(v.getRecommendation())
                        .details(v.getDetails())
                        .fieldValues(v.getFieldValues() != null ? new HashMap<>(v.getFieldValues()) : new HashMap<>())
                        .build())
                .collect(Collectors.toList());

        PeerReview review = PeerReview.builder()
                .assessmentId(assessmentId)
                .snapshotFieldValues(assessment.getFieldValues() != null
                        ? new HashMap<>(assessment.getFieldValues()) : new HashMap<>())
                .vulnerabilities(snapshotVulns)
                .submittedByUserId(actorId(userId, authentication))
                .createdAt(LocalDateTime.now())
                .status(PeerReviewStatus.PENDING)
                .build();

        PeerReview saved = peerReviewRepository.save(review);

        assessment.setPeerReviewStatus(AssessmentPeerReviewStatus.IN_PEER_REVIEW);
        assessment.setActivePeerReviewId(saved.getId());
        assessmentRepository.save(assessment);

        log.info("Assessment {} submitted for peer review (reviewId={})", assessmentId, saved.getId());
        extensionEventService.assessmentChanged(assessmentId,
            com.faction.extender.AssessmentManager.Operation.PeerReviewCreated);
        return enrich(PeerReviewDto.fromEntity(saved));
    }

    /**
     * Returns paginated queue of reviews with status PENDING or IN_REVIEW.
     */
    public Page<PeerReviewDto> getQueue(Pageable pageable) {
        return getQueue(pageable, null);
    }

    /**
     * The queue's display-only columns. {@code assessmentName}, {@code submittedByName} and
     * {@code reviewedByName} are resolved per row by {@link #enrich}, so there is no column to
     * order by in the database — sorting on one has to happen after enrichment. The remaining
     * columns are entity properties and stay on the fast DB-paged path.
     */
    private static final Map<String, Comparator<PeerReviewDto>> ENRICHED_SORTS = Map.of(
            "assessmentName", Comparator.comparing(PeerReviewDto::getAssessmentName,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)),
            "submittedByName", Comparator.comparing(PeerReviewDto::getSubmittedByName,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)),
            "reviewedByName", Comparator.comparing(PeerReviewDto::getReviewedByName,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));

    public Page<PeerReviewDto> getQueue(Pageable pageable, Authentication authentication) {
        List<PeerReviewStatus> open = List.of(PeerReviewStatus.PENDING, PeerReviewStatus.IN_REVIEW);

        boolean unrestricted = authentication == null
                || hasAuthority(authentication, SUPER_ADMIN)
                || hasAuthority(authentication, Permission.PEERREVIEW_READ_ALL.getPermission());

        Comparator<PeerReviewDto> enrichedSort = enrichedSort(pageable);

        if (unrestricted && enrichedSort == null) {
            return peerReviewRepository.findByStatusIn(open, pageable)
                    .map(r -> enrich(PeerReviewDto.fromEntity(r)));
        }

        // Either the caller is team-scoped (visibility is a per-row check the query can't express)
        // or the sort is on an enriched column — both need the full open queue materialized before
        // it can be ordered and cut into a page. The open queue is bounded by design.
        List<PeerReviewDto> visible = peerReviewRepository.findByStatusInOrderByCreatedAtDesc(open).stream()
                .filter(r -> unrestricted
                        || assessmentRepository.findByIdAndDeletedAtIsNull(r.getAssessmentId())
                        .map(a -> canRead(authentication, a))
                        .orElse(false))
                .map(r -> enrich(PeerReviewDto.fromEntity(r)))
                .collect(Collectors.toList());

        sortInMemory(visible, pageable, enrichedSort);

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), visible.size());
        List<PeerReviewDto> page = start >= visible.size() ? List.of() : visible.subList(start, end);
        return new PageImpl<>(page, pageable, visible.size());
    }

    /** The comparator for {@code pageable}'s sort key if it names an enriched column, else null. */
    private static Comparator<PeerReviewDto> enrichedSort(Pageable pageable) {
        if (pageable.getSort().isUnsorted()) return null;
        return ENRICHED_SORTS.get(pageable.getSort().iterator().next().getProperty());
    }

    /**
     * Order the materialized queue. Enriched columns use their comparator; entity columns fall back
     * to the repository's createdAt-desc order, which the list already arrives in.
     */
    private static void sortInMemory(List<PeerReviewDto> rows, Pageable pageable,
                                     Comparator<PeerReviewDto> enrichedSort) {
        if (pageable.getSort().isUnsorted()) return;
        Sort.Order order = pageable.getSort().iterator().next();

        Comparator<PeerReviewDto> comparator = enrichedSort != null ? enrichedSort
                : switch (order.getProperty()) {
                    case "createdAt" -> Comparator.comparing(PeerReviewDto::getCreatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder()));
                    case "completedAt" -> Comparator.comparing(PeerReviewDto::getCompletedAt,
                            Comparator.nullsLast(Comparator.naturalOrder()));
                    case "status" -> Comparator.comparing(PeerReviewDto::getStatus,
                            Comparator.nullsLast(Comparator.naturalOrder()));
                    default -> null;
                };
        if (comparator == null) return;

        // Tiebreak on id so paging over equal keys stays stable between requests.
        comparator = comparator.thenComparing(PeerReviewDto::getId,
                Comparator.nullsLast(Comparator.naturalOrder()));
        rows.sort(order.isDescending() ? comparator.reversed() : comparator);
    }

    /**
     * Returns the full peer review history for an assessment.
     */
    public List<PeerReviewDto> getByAssessment(String assessmentId) {
        return getByAssessment(assessmentId, null);
    }

    public List<PeerReviewDto> getByAssessment(String assessmentId, Authentication authentication) {
        checkReadAccess(authentication, getAssessmentOrThrow(assessmentId));
        return peerReviewRepository.findByAssessmentIdOrderByCreatedAtDesc(assessmentId)
                .stream()
                .map(r -> enrich(PeerReviewDto.fromEntity(r)))
                .collect(Collectors.toList());
    }

    /**
     * Returns a single peer review by ID.
     */
    public PeerReviewDto getPeerReview(String reviewId) {
        return getPeerReview(reviewId, null);
    }

    public PeerReviewDto getPeerReview(String reviewId, Authentication authentication) {
        PeerReview review = getPeerReviewOrThrow(reviewId);
        checkReadAccess(authentication, getAssessmentOrThrow(review.getAssessmentId()));
        return enrich(PeerReviewDto.fromEntity(review));
    }

    /**
     * Reviewer claims the review (sets status to IN_REVIEW).
     */
    public PeerReviewDto startReview(String reviewId, String userId) {
        return startReview(reviewId, userId, null);
    }

    public PeerReviewDto startReview(String reviewId, String userId, Authentication authentication) {
        PeerReview review = getPeerReviewOrThrow(reviewId);
        checkEditAccess(authentication, getAssessmentOrThrow(review.getAssessmentId()));
        checkNotSelfReview(authentication, review);
        if (review.getStatus() != PeerReviewStatus.PENDING) {
            throw new IllegalStateException("Review is not in PENDING status");
        }
        review.setStatus(PeerReviewStatus.IN_REVIEW);
        review.setReviewedByUserId(actorId(userId, authentication));
        recordReviewer(review, actorId(userId, authentication));
        return enrich(PeerReviewDto.fromEntity(peerReviewRepository.save(review)));
    }

    /**
     * Reviewer saves their edits and notes.
     */
    public PeerReviewDto updateReview(String reviewId, UpdatePeerReviewRequest request, String userId) {
        return updateReview(reviewId, request, userId, null);
    }

    public PeerReviewDto updateReview(String reviewId, UpdatePeerReviewRequest request, String userId,
                                      Authentication authentication) {
        PeerReview review = getPeerReviewOrThrow(reviewId);
        checkEditAccess(authentication, getAssessmentOrThrow(review.getAssessmentId()));
        checkNotSelfReview(authentication, review);

        if (request.getRevisedFieldValues() != null) {
            review.setRevisedFieldValues(new HashMap<>(request.getRevisedFieldValues()));
        }
        if (request.getFieldNotes() != null) {
            review.setFieldNotes(new HashMap<>(request.getFieldNotes()));
        }
        if (request.getVulnerabilities() != null) {
            List<PeerReviewVulnerability> updatedVulns = request.getVulnerabilities().stream()
                    .map(PeerReviewVulnerabilityDto::toEntity)
                    .collect(Collectors.toList());
            review.setVulnerabilities(updatedVulns);
        }

        // Saving is what makes someone a reviewer of this review — several people can be working
        // it at once, and only the first of them is the one who claimed it.
        recordReviewer(review, actorId(userId, authentication));

        PeerReviewDto saved = enrich(PeerReviewDto.fromEntity(peerReviewRepository.save(review)));

        // Everyone else on this review sees the edit land. Locking a region only tells the other
        // reviewers not to type there; without this they would sit watching stale text. The saver
        // is excluded — their own client is already the source of what was just written.
        lockService.broadcastEdits(reviewId, userId,
                saved.getRevisedFieldValues(), saved.getFieldNotes(), saved.getVulnerabilities());

        return saved;
    }

    /**
     * Reviewer marks the review as done; assessment transitions to NEEDS_ACCEPTANCE.
     */
    public PeerReviewDto completeReview(String reviewId, String userId) {
        return completeReview(reviewId, userId, null);
    }

    public PeerReviewDto completeReview(String reviewId, String userId, Authentication authentication) {
        PeerReview review = getPeerReviewOrThrow(reviewId);
        checkEditAccess(authentication, getAssessmentOrThrow(review.getAssessmentId()));
        checkNotSelfReview(authentication, review);
        if (review.getStatus() != PeerReviewStatus.IN_REVIEW) {
            throw new IllegalStateException("Review must be IN_REVIEW to complete");
        }

        review.setStatus(PeerReviewStatus.COMPLETED);
        review.setCompletedAt(LocalDateTime.now());
        PeerReview saved = peerReviewRepository.save(review);

        Assessment assessment = getAssessmentOrThrow(review.getAssessmentId());
        assessment.setPeerReviewStatus(AssessmentPeerReviewStatus.NEEDS_ACCEPTANCE);
        assessmentRepository.save(assessment);

        log.info("Peer review {} completed; assessment {} needs acceptance", reviewId, review.getAssessmentId());
        extensionEventService.assessmentChanged(review.getAssessmentId(),
            com.faction.extender.AssessmentManager.Operation.PeerReviewCompleted);
        return enrich(PeerReviewDto.fromEntity(saved));
    }

    /**
     * Assessor accepts selected changes and applies them to live documents.
     * Transitions assessment to COMPLETE and clears activePeerReviewId.
     */
    public PeerReviewDto acceptChanges(String reviewId, AcceptPeerReviewRequest request, String userId) {
        return acceptChanges(reviewId, request, userId, null);
    }

    public PeerReviewDto acceptChanges(String reviewId, AcceptPeerReviewRequest request, String userId,
                                       Authentication authentication) {
        PeerReview review = getPeerReviewOrThrow(reviewId);
        checkEditAccess(authentication, getAssessmentOrThrow(review.getAssessmentId()));
        if (review.getStatus() != PeerReviewStatus.COMPLETED) {
            throw new IllegalStateException("Review must be COMPLETED before accepting changes");
        }

        Assessment assessment = getAssessmentOrThrow(review.getAssessmentId());

        // Apply accepted assessment-level field changes. Blank revised values
        // never overwrite existing content — an accidental empty editor state
        // must not wipe live data on accept.
        if (request.getAcceptedAssessmentFieldIds() != null && !request.getAcceptedAssessmentFieldIds().isEmpty()) {
            Map<String, String> revised = review.getRevisedFieldValues();
            if (revised != null) {
                for (String fieldId : request.getAcceptedAssessmentFieldIds()) {
                    if (revised.containsKey(fieldId)
                            && !isBlankOverwrite(revised.get(fieldId), assessment.getFieldValues().get(fieldId))) {
                        assessment.getFieldValues().put(fieldId, revised.get(fieldId));
                    }
                }
            }
        }
        assessment.setPeerReviewStatus(AssessmentPeerReviewStatus.COMPLETE);
        assessment.setActivePeerReviewId(null);
        assessmentRepository.save(assessment);

        // Apply accepted vulnerability changes
        if (request.getAcceptedVulnerabilityChanges() != null) {
            Map<String, PeerReviewVulnerability> vulnMap = review.getVulnerabilities().stream()
                    .collect(Collectors.toMap(PeerReviewVulnerability::getVulnerabilityId, v -> v));

            for (Map.Entry<String, List<String>> entry : request.getAcceptedVulnerabilityChanges().entrySet()) {
                String vulnId = entry.getKey();
                List<String> acceptedFields = entry.getValue();
                PeerReviewVulnerability prVuln = vulnMap.get(vulnId);
                if (prVuln == null || acceptedFields == null || acceptedFields.isEmpty()) continue;

                vulnerabilityRepository.findById(vulnId).ifPresent(vuln -> {
                    for (String field : acceptedFields) {
                        switch (field) {
                            case "description":
                                if (prVuln.getRevisedDescription() != null
                                        && !isBlankOverwrite(prVuln.getRevisedDescription(), vuln.getDescription())) {
                                    vuln.setDescription(prVuln.getRevisedDescription());
                                }
                                break;
                            case "recommendation":
                                if (prVuln.getRevisedRecommendation() != null
                                        && !isBlankOverwrite(prVuln.getRevisedRecommendation(), vuln.getRecommendation())) {
                                    vuln.setRecommendation(prVuln.getRevisedRecommendation());
                                }
                                break;
                            case "details":
                                if (prVuln.getRevisedDetails() != null
                                        && !isBlankOverwrite(prVuln.getRevisedDetails(), vuln.getDetails())) {
                                    vuln.setDetails(prVuln.getRevisedDetails());
                                }
                                break;
                            default:
                                // custom field
                                if (prVuln.getRevisedFieldValues() != null && prVuln.getRevisedFieldValues().containsKey(field)
                                        && !isBlankOverwrite(prVuln.getRevisedFieldValues().get(field),
                                                vuln.getFieldValues().get(field))) {
                                    vuln.getFieldValues().put(field, prVuln.getRevisedFieldValues().get(field));
                                }
                                break;
                        }
                    }
                    vulnerabilityRepository.save(vuln);
                });
            }
        }

        log.info("Peer review {} changes accepted; assessment {} is COMPLETE", reviewId, review.getAssessmentId());
        extensionEventService.assessmentChanged(review.getAssessmentId(),
            com.faction.extender.AssessmentManager.Operation.PeerReviewAccepted);
        return enrich(PeerReviewDto.fromEntity(peerReviewRepository.findById(reviewId).orElse(review)));
    }

    /**
     * True when applying {@code revised} would wipe existing content: the
     * revised value has no visible text (empty, whitespace, or empty HTML
     * like {@code <p><br></p>}) while the current value has some.
     */
    private static boolean isBlankOverwrite(String revised, String current) {
        return !hasVisibleText(revised) && hasVisibleText(current);
    }

    private static boolean hasVisibleText(String html) {
        if (html == null) return false;
        String text = html.replaceAll("<[^>]*>", "").replace("&nbsp;", " ").trim();
        return !text.isEmpty();
    }

    /**
     * Assessor rejects the entire review; assessment returns to IN_PROGRESS.
     */
    public PeerReviewDto rejectReview(String reviewId, String userId) {
        return rejectReview(reviewId, userId, null);
    }

    public PeerReviewDto rejectReview(String reviewId, String userId, Authentication authentication) {
        PeerReview review = getPeerReviewOrThrow(reviewId);

        Assessment assessment = getAssessmentOrThrow(review.getAssessmentId());
        checkEditAccess(authentication, assessment);
        assessment.setPeerReviewStatus(AssessmentPeerReviewStatus.IN_PROGRESS);
        assessment.setActivePeerReviewId(null);
        assessmentRepository.save(assessment);

        // Mark the review document as completed (discarded)
        review.setStatus(PeerReviewStatus.COMPLETED);
        review.setCompletedAt(LocalDateTime.now());
        PeerReview saved = peerReviewRepository.save(review);

        log.info("Peer review {} rejected; assessment {} unlocked", reviewId, review.getAssessmentId());
        return enrich(PeerReviewDto.fromEntity(saved));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Assessment getAssessmentOrThrow(String assessmentId) {
        return assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assessment not found: " + assessmentId));
    }

    private PeerReview getPeerReviewOrThrow(String reviewId) {
        return peerReviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Peer review not found: " + reviewId));
    }

    /** Enriches a PeerReviewDto with display names for submitter/reviewer. */
    private PeerReviewDto enrich(PeerReviewDto dto) {
        if (dto.getSubmittedByUserId() != null) {
            userRepository.findById(dto.getSubmittedByUserId())
                    .map(u -> u.getFirstName() + " " + u.getLastName())
                    .ifPresent(dto::setSubmittedByName);
        }
        if (dto.getReviewedByUserId() != null) {
            userRepository.findById(dto.getReviewedByUserId())
                    .map(u -> u.getFirstName() + " " + u.getLastName())
                    .ifPresent(dto::setReviewedByName);
        }
        // Reviews from before the list is populated still have a claimer, so fall back to them
        // rather than reporting a review with a reviewer as having none.
        List<String> reviewerIds = dto.getReviewerUserIds() == null || dto.getReviewerUserIds().isEmpty()
                ? (dto.getReviewedByUserId() != null ? List.of(dto.getReviewedByUserId()) : List.of())
                : dto.getReviewerUserIds();
        if (!reviewerIds.isEmpty()) {
            Map<String, String> names = userRepository.findAllById(reviewerIds).stream()
                    .collect(Collectors.toMap(User::getId, u -> u.getFirstName() + " " + u.getLastName()));
            dto.setReviewerUserIds(new ArrayList<>(reviewerIds));
            dto.setReviewerNames(reviewerIds.stream()
                    .map(id -> names.getOrDefault(id, id))
                    .collect(Collectors.toList()));
        }
        if (dto.getAssessmentId() != null) {
            assessmentRepository.findByIdAndDeletedAtIsNull(dto.getAssessmentId())
                    .map(a -> a.getName())
                    .ifPresent(dto::setAssessmentName);
        }
        return dto;
    }
}
