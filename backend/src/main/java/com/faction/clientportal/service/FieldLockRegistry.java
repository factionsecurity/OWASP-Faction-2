package com.faction.clientportal.service;

import com.faction.clientportal.model.FieldLock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Per-field edit locks and the SSE fan-out that advertises them, for one kind of record.
 *
 * <p>Deliberately not a bean: each owning service holds its own instance so assessments and peer
 * reviews keep separate lock namespaces and separate subscriber sets. A "scope" is whatever record
 * the locks hang off — an assessment id for {@link AssessmentLockService}, a review id for
 * {@link PeerReviewLockService} — and a "field" is one editable region within it.
 *
 * <p>Sweeping and heartbeats are driven by the owner's {@code @Scheduled} methods rather than
 * this class's own, since two instances of a self-scheduling bean would each need their own
 * trigger anyway.
 */
public class FieldLockRegistry {

    // scopeId -> (clientKey -> SseEmitter)
    private final Map<String, Map<String, SseEmitter>> emitters = new ConcurrentHashMap<>();
    // scopeId -> (fieldId -> FieldLock)
    private final Map<String, Map<String, FieldLock>> locks = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final long ttlSeconds;

    public FieldLockRegistry(ObjectMapper objectMapper, long ttlSeconds) {
        this.objectMapper = objectMapper;
        this.ttlSeconds = ttlSeconds;
    }

    /** Register a new SSE subscriber; immediately sends current_locks. */
    public SseEmitter subscribe(String scopeId, String clientKey) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        emitters.computeIfAbsent(scopeId, k -> new ConcurrentHashMap<>()).put(clientKey, emitter);
        Runnable cleanup = () -> {
            Map<String, SseEmitter> m = emitters.get(scopeId);
            if (m != null) { m.remove(clientKey); if (m.isEmpty()) emitters.remove(scopeId); }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        sendCurrentLocks(scopeId, emitter);
        return emitter;
    }

    /**
     * Acquire or refresh. Returns false if locked by another non-expired user.
     *
     * <p>{@code lastActivity} must be stamped as the lock is built. {@link FieldLock#isExpired}
     * treats a null timestamp as expired, so a lock created without one is born dead: the
     * next sweep drops it, and in the meantime every rival client is granted it. Two users
     * on the same field then trade ownership back and forth, each seeing the other flagged
     * as editing while neither is typing.
     */
    public boolean acquireLock(String scopeId, String fieldId, String username, String displayName) {
        Map<String, FieldLock> scopeLocks = locks.computeIfAbsent(scopeId, k -> new ConcurrentHashMap<>());
        FieldLock existing = scopeLocks.get(fieldId);
        if (existing != null && !existing.getUsername().equals(username) && !existing.isExpired(ttlSeconds)) {
            return false;
        }
        if (existing != null && existing.getUsername().equals(username)) {
            // Already ours and already advertised — a keep-alive changes nothing for
            // other clients, so don't re-broadcast. Editing re-stamps this every few
            // seconds; re-announcing each time would churn every subscriber's state.
            existing.refreshActivity();
            return true;
        }
        scopeLocks.put(fieldId, FieldLock.builder()
                .fieldId(fieldId).username(username).displayName(displayName)
                .lastActivity(Instant.now())
                .build());
        broadcastToAll(scopeId, "field_locked",
            Map.of("fieldId", fieldId, "username", username, "displayName", displayName));
        return true;
    }

    /** Explicit release (section navigation). */
    public void releaseLock(String scopeId, String fieldId, String username) {
        Map<String, FieldLock> scopeLocks = locks.get(scopeId);
        if (scopeLocks == null) return;
        FieldLock lock = scopeLocks.get(fieldId);
        if (lock != null && lock.getUsername().equals(username)) {
            scopeLocks.remove(fieldId);
            broadcastToAll(scopeId, "field_unlocked", Map.of("fieldId", fieldId));
        }
    }

    /** Broadcast to every client in the scope except those of {@code excludeUsername}. */
    public void broadcastExcludingUser(String scopeId, String type, Object payload, String excludeUsername) {
        Map<String, SseEmitter> scopeEmitters = emitters.get(scopeId);
        if (scopeEmitters == null) return;
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, SseEmitter> e : scopeEmitters.entrySet()) {
            if (e.getKey().startsWith(excludeUsername + ":")) continue;
            try { sendEvent(e.getValue(), type, payload); }
            catch (Exception ex) { dead.add(e.getKey()); }
        }
        dead.forEach(scopeEmitters::remove);
    }

    /** Drop locks whose holder has stopped editing for the full TTL, telling everyone. */
    public void expireStaleLocks() {
        for (Map.Entry<String, Map<String, FieldLock>> entry : locks.entrySet()) {
            String scopeId = entry.getKey();
            Map<String, FieldLock> scopeLocks = entry.getValue();
            scopeLocks.values().stream().filter(l -> l.isExpired(ttlSeconds))
                .map(FieldLock::getFieldId).collect(Collectors.toList())
                .forEach(fieldId -> {
                    scopeLocks.remove(fieldId);
                    broadcastToAll(scopeId, "field_unlocked", Map.of("fieldId", fieldId));
                });
            if (scopeLocks.isEmpty()) locks.remove(scopeId);
        }
    }

    /** Keep streams warm so proxies don't buffer them closed. */
    public void sendHeartbeats() {
        for (Map<String, SseEmitter> scopeEmitters : emitters.values()) {
            List<String> dead = new ArrayList<>();
            for (Map.Entry<String, SseEmitter> e : scopeEmitters.entrySet()) {
                try { e.getValue().send(SseEmitter.event().comment("heartbeat")); }
                catch (Exception ex) { dead.add(e.getKey()); }
            }
            dead.forEach(scopeEmitters::remove);
        }
    }

    private void sendCurrentLocks(String scopeId, SseEmitter emitter) {
        Map<String, FieldLock> scopeLocks = locks.getOrDefault(scopeId, Map.of());
        List<Map<String, String>> list = scopeLocks.values().stream()
            .filter(l -> !l.isExpired(ttlSeconds))
            .map(l -> Map.of("fieldId", l.getFieldId(), "username", l.getUsername(), "displayName", l.getDisplayName()))
            .collect(Collectors.toList());
        try { sendEvent(emitter, "current_locks", Map.of("locks", list)); } catch (Exception ignored) {}
    }

    public void broadcastToAll(String scopeId, String type, Object payload) {
        Map<String, SseEmitter> scopeEmitters = emitters.get(scopeId);
        if (scopeEmitters == null) return;
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, SseEmitter> e : scopeEmitters.entrySet()) {
            try { sendEvent(e.getValue(), type, payload); }
            catch (Exception ex) { dead.add(e.getKey()); }
        }
        dead.forEach(scopeEmitters::remove);
    }

    private void sendEvent(SseEmitter emitter, String type, Object payload) throws IOException {
        emitter.send(SseEmitter.event().name(type).data(objectMapper.writeValueAsString(payload)));
    }
}
