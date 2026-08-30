package com.faction.clientportal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@Service
@Slf4j
public class AssessmentLockService {

    /**
     * How long a lock survives with no further edit activity.
     *
     * <p>A lock is held for this long after the user's <em>last edit</em>, and nothing else
     * releases it automatically — not switching records, not leaving the page. Clients
     * re-stamp it every few seconds while typing, and once more shortly after typing stops,
     * so the countdown always runs from the final keystroke rather than the last request.
     *
     * <p>Package-private so tests can assert the guarantee against the real value.
     */
    static final long LOCK_TTL_SECONDS = 10L;

    /** Assessment-scoped locks and subscribers; peer review keeps its own separate registry. */
    private final FieldLockRegistry registry;

    public AssessmentLockService(ObjectMapper objectMapper) {
        this.registry = new FieldLockRegistry(objectMapper, LOCK_TTL_SECONDS);
    }

    /** Register a new SSE subscriber; immediately sends current_locks. */
    public SseEmitter subscribe(String assessmentId, String clientKey) {
        return registry.subscribe(assessmentId, clientKey);
    }

    /** Acquire or refresh. Returns false if locked by another non-expired user. */
    public boolean acquireLock(String assessmentId, String fieldId, String username, String displayName) {
        return registry.acquireLock(assessmentId, fieldId, username, displayName);
    }

    /** Explicit release (section navigation). */
    public void releaseLock(String assessmentId, String fieldId, String username) {
        registry.releaseLock(assessmentId, fieldId, username);
    }

    /** Broadcast field_updated to all clients EXCEPT the saver's username. */
    public void broadcastFieldUpdated(String assessmentId, String fieldId, String value, String excludeUsername) {
        registry.broadcastExcludingUser(assessmentId, "field_updated",
            Map.of("fieldId", fieldId, "value", value), excludeUsername);
    }

    /**
     * Notify every client viewing this assessment that its vulnerability list
     * changed (created/updated/deleted/reordered) so they can refetch. Sent to
     * ALL subscribers including the author — a refetch is idempotent, and the
     * author's client may itself be stale (e.g. MCP/API writes from the same user).
     */
    public void broadcastVulnerabilitiesChanged(String assessmentId, String action, String vulnerabilityId) {
        Map<String, Object> payload = vulnerabilityId != null
            ? Map.of("action", action, "vulnerabilityId", vulnerabilityId)
            : Map.of("action", action);
        registry.broadcastToAll(assessmentId, "vulnerabilities_changed", payload);
    }

    /** Expire stale locks every 2 seconds. */
    @Scheduled(fixedDelay = 2000)
    public void expireStaleLocks() {
        registry.expireStaleLocks();
    }

    /** Heartbeat every 30s to prevent proxy buffering. */
    @Scheduled(fixedDelay = 30000)
    public void sendHeartbeats() {
        registry.sendHeartbeats();
    }
}
