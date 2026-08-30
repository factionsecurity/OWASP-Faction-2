package com.faction.clientportal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Peer review locks are per-editor, not per-review: two reviewers working the same review at once
 * is the normal case, and only the exact region someone is typing into may be blocked for the
 * other. A review-wide lock would make collaborative review impossible.
 */
class PeerReviewLockServiceTest {

    private static final String REVIEW = "review-1";
    private static final String VULN_1_DESC = "vuln:v1:description";
    private static final String VULN_2_DESC = "vuln:v2:description";

    private PeerReviewLockService service;

    @BeforeEach
    void setUp() {
        service = new PeerReviewLockService(new ObjectMapper());
    }

    /** The behaviour the feature exists for. */
    @Test
    void twoReviewersEditingDifferentVulnerabilitiesEachBlockOnlyTheOther() {
        assertThat(service.acquireLock(REVIEW, VULN_1_DESC, "amy", "Amy")).isTrue();
        assertThat(service.acquireLock(REVIEW, VULN_2_DESC, "ben", "Ben")).isTrue();

        // Amy holds vuln 1, so Ben cannot take it...
        assertThat(service.acquireLock(REVIEW, VULN_1_DESC, "ben", "Ben")).isFalse();
        // ...and Ben holds vuln 2, so Amy cannot take that.
        assertThat(service.acquireLock(REVIEW, VULN_2_DESC, "amy", "Amy")).isFalse();

        // Neither is shut out of the rest of the review.
        assertThat(service.acquireLock(REVIEW, "vuln:v3:description", "amy", "Amy")).isTrue();
        assertThat(service.acquireLock(REVIEW, "vuln:v4:recommendation", "ben", "Ben")).isTrue();
    }

    /** A vulnerability's own fields lock independently — editing the description leaves the rest open. */
    @Test
    void fieldsOfOneVulnerabilityLockIndependently() {
        assertThat(service.acquireLock(REVIEW, "vuln:v1:description", "amy", "Amy")).isTrue();

        assertThat(service.acquireLock(REVIEW, "vuln:v1:recommendation", "ben", "Ben")).isTrue();
        assertThat(service.acquireLock(REVIEW, "vuln:v1:descriptionNotes", "ben", "Ben")).isTrue();
        assertThat(service.acquireLock(REVIEW, "vuln:v1:description", "ben", "Ben")).isFalse();
    }

    /** Locks are namespaced per review, so the same key in another review is unaffected. */
    @Test
    void locksDoNotLeakBetweenReviews() {
        assertThat(service.acquireLock(REVIEW, VULN_1_DESC, "amy", "Amy")).isTrue();
        assertThat(service.acquireLock("review-2", VULN_1_DESC, "ben", "Ben")).isTrue();
    }

    @Test
    void freshlyAcquiredLockIsNotAlreadyExpired() {
        assertThat(service.acquireLock(REVIEW, VULN_1_DESC, "amy", "Amy")).isTrue();

        // The sweep runs every 2s and drops anything expired; a lock taken moments ago survives it.
        service.expireStaleLocks();

        assertThat(service.acquireLock(REVIEW, VULN_1_DESC, "ben", "Ben"))
                .as("a lock taken seconds ago must still block a second user")
                .isFalse();
    }

    @Test
    void theHolderCanKeepRefreshingWithoutLosingTheLock() {
        assertThat(service.acquireLock(REVIEW, VULN_1_DESC, "amy", "Amy")).isTrue();
        assertThat(service.acquireLock(REVIEW, VULN_1_DESC, "amy", "Amy")).isTrue();
        assertThat(service.acquireLock(REVIEW, VULN_1_DESC, "ben", "Ben")).isFalse();
    }

    @Test
    void releasingHandsTheEditorToTheNextUser() {
        service.acquireLock(REVIEW, VULN_1_DESC, "amy", "Amy");
        service.releaseLock(REVIEW, VULN_1_DESC, "amy");

        assertThat(service.acquireLock(REVIEW, VULN_1_DESC, "ben", "Ben")).isTrue();
    }

    @Test
    void releaseByNonHolderIsIgnored() {
        service.acquireLock(REVIEW, VULN_1_DESC, "amy", "Amy");
        service.releaseLock(REVIEW, VULN_1_DESC, "ben");

        assertThat(service.acquireLock(REVIEW, VULN_1_DESC, "ben", "Ben"))
                .as("ben must not be able to release a lock he does not hold")
                .isFalse();
    }

    /** Matches the assessment TTL, so both surfaces behave the same after typing stops. */
    @Test
    void ttlMatchesTheAssessmentLock() {
        assertThat(PeerReviewLockService.LOCK_TTL_SECONDS)
                .isEqualTo(AssessmentLockService.LOCK_TTL_SECONDS);
    }
}
