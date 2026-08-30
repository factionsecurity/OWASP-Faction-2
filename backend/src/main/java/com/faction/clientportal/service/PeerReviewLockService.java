package com.faction.clientportal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * Edit locks for a peer review, one per editable region rather than one for the whole review.
 *
 * <p>A review is a page of independent editors — every rich-text assessment field, every
 * vulnerability's description/recommendation/details, and the reviewer note beside each. Two
 * reviewers working the same review at once is normal and useful, so the lock has to be as narrow
 * as the thing being typed into: whoever starts editing a given editor holds it, and everyone else
 * sees just that one blocked while the rest of the review stays open to them.
 *
 * <p>The lock key is a composite built by the client (see {@code lockKey} in PeerReviewEditor),
 * not an entity id — the server treats it as an opaque string, which is what lets one review carry
 * a lock per editor without the backend modelling every region.
 */
@Service
@Slf4j
public class PeerReviewLockService {

    /**
     * Matches {@code AssessmentLockService.LOCK_TTL_SECONDS}: a lock outlives the last keystroke
     * by this long and nothing else drops it, so a reviewer who pauses to read keeps their place.
     */
    static final long LOCK_TTL_SECONDS = 10L;

    private final FieldLockRegistry registry;

    public PeerReviewLockService(ObjectMapper objectMapper) {
        this.registry = new FieldLockRegistry(objectMapper, LOCK_TTL_SECONDS);
    }

    /** Register a new SSE subscriber for a review; immediately sends current_locks. */
    public SseEmitter subscribe(String reviewId, String clientKey) {
        return registry.subscribe(reviewId, clientKey);
    }

    /** Acquire or refresh one editor's lock. False when another active user holds it. */
    public boolean acquireLock(String reviewId, String fieldId, String username, String displayName) {
        return registry.acquireLock(reviewId, fieldId, username, displayName);
    }

    /** Release a lock the caller holds; a release by anyone else is ignored. */
    public void releaseLock(String reviewId, String fieldId, String username) {
        registry.releaseLock(reviewId, fieldId, username);
    }

    /**
     * Push a reviewer's saved edits to everyone else on the review, so a locked editor shows the
     * text arriving rather than a frozen copy of what it held when the lock was taken.
     *
     * <p>Sends the whole editable payload rather than a single field: a review saves as one
     * document, and the receiving client is the side that knows which regions it may apply (any it
     * does not itself hold). The saver is excluded — their client already has this content.
     */
    public void broadcastEdits(String reviewId, String savedByUsername,
                               Object revisedFieldValues, Object fieldNotes, Object vulnerabilities) {
        registry.broadcastExcludingUser(reviewId, "review_updated", Map.of(
                "revisedFieldValues", revisedFieldValues == null ? Map.of() : revisedFieldValues,
                "fieldNotes", fieldNotes == null ? Map.of() : fieldNotes,
                "vulnerabilities", vulnerabilities == null ? List.of() : vulnerabilities
        ), savedByUsername);
    }

    @Scheduled(fixedDelay = 2000)
    public void expireStaleLocks() {
        registry.expireStaleLocks();
    }

    @Scheduled(fixedDelay = 30000)
    public void sendHeartbeats() {
        registry.sendHeartbeats();
    }
}
