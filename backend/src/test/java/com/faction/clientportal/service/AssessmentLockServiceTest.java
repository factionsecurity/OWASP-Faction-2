package com.faction.clientportal.service;

import com.faction.clientportal.model.FieldLock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Field locks are what stop two pentesters editing the same vulnerability at once.
 *
 * <p>The invariant under test: a freshly acquired lock is <em>live</em>. A lock that is
 * born expired is worse than no lock at all — every client that asks for it is granted
 * it, so two users hand ownership back and forth and each sees the other flagged as
 * "editing" while neither is typing.
 */
class AssessmentLockServiceTest {

    private static final String ASSESSMENT = "assessment-1";
    private static final String FIELD = "vuln_abc";

    private AssessmentLockService service;

    @BeforeEach
    void setUp() {
        service = new AssessmentLockService(new ObjectMapper());
    }

    @Test
    void freshlyAcquiredLockIsNotAlreadyExpired() {
        assertThat(service.acquireLock(ASSESSMENT, FIELD, "amy", "Amy")).isTrue();

        // The sweep runs every 2s and drops anything expired. A lock taken moments
        // ago must survive it.
        service.expireStaleLocks();

        assertThat(service.acquireLock(ASSESSMENT, FIELD, "ben", "Ben"))
                .as("a lock taken seconds ago must still block a second user")
                .isFalse();
    }

    @Test
    void secondUserIsRefusedWhileTheHolderIsActive() {
        assertThat(service.acquireLock(ASSESSMENT, FIELD, "amy", "Amy")).isTrue();
        assertThat(service.acquireLock(ASSESSMENT, FIELD, "ben", "Ben")).isFalse();
        assertThat(service.acquireLock(ASSESSMENT, FIELD, "ben", "Ben")).isFalse();

        // ...and the holder can keep refreshing it.
        assertThat(service.acquireLock(ASSESSMENT, FIELD, "amy", "Amy")).isTrue();
        assertThat(service.acquireLock(ASSESSMENT, FIELD, "ben", "Ben")).isFalse();
    }

    @Test
    void releasingHandsTheFieldToTheNextUser() {
        service.acquireLock(ASSESSMENT, FIELD, "amy", "Amy");
        service.releaseLock(ASSESSMENT, FIELD, "amy");

        assertThat(service.acquireLock(ASSESSMENT, FIELD, "ben", "Ben")).isTrue();
    }

    @Test
    void releaseByNonHolderIsIgnored() {
        service.acquireLock(ASSESSMENT, FIELD, "amy", "Amy");
        service.releaseLock(ASSESSMENT, FIELD, "ben");

        assertThat(service.acquireLock(ASSESSMENT, FIELD, "ben", "Ben"))
                .as("ben must not be able to release a lock he does not hold")
                .isFalse();
    }

    /**
     * The agreed contract: a lock is held for the full TTL after the last edit, and only a
     * lack of edits releases it. Clients re-stamp {@code lastActivity} on every edit, so
     * "a second short of the TTL since the last stamp" is "that long since the user typed".
     */
    @Test
    void lockSurvivesUntilEditingHasStoppedForTheFullTtl() {
        long ttl = AssessmentLockService.LOCK_TTL_SECONDS;
        assertThat(ttl).isGreaterThanOrEqualTo(10L);

        FieldLock lock = FieldLock.builder()
                .fieldId(FIELD).username("amy").displayName("Amy")
                .lastActivity(Instant.now().minusSeconds(ttl - 1))
                .build();
        assertThat(lock.isExpired(ttl))
                .as("a second short of the TTL, the lock must still be held")
                .isFalse();

        lock.refreshActivity();
        assertThat(lock.isExpired(ttl)).isFalse();
    }

    @Test
    void lockIsReleasedOnceEditingHasStoppedForTheFullTtl() {
        FieldLock lock = FieldLock.builder()
                .fieldId(FIELD).username("amy").displayName("Amy")
                .lastActivity(Instant.now().minusSeconds(AssessmentLockService.LOCK_TTL_SECONDS + 1))
                .build();

        assertThat(lock.isExpired(AssessmentLockService.LOCK_TTL_SECONDS)).isTrue();
    }
}
