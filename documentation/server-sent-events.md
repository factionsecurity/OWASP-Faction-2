# Server-Sent Events & Collaborative Field Locking

## Overview

Multiple assessors can work on the same assessment simultaneously. To prevent two users from overwriting each other's changes on the same field, the system uses a field-level locking mechanism delivered via Server-Sent Events (SSE). Each browser tab that opens an assessment subscribes to a persistent event stream. When a user focuses a field, they acquire a short-lived lock that is broadcast to all other subscribers, causing those fields to appear disabled in their UIs.

---

## SSE Connection

### Endpoint

```
GET /api/v1/assessments/{id}/events?clientId=<optional>
Content-Type: text/event-stream
Authorization: Bearer <jwt>
```

**Permission required:** `super_admin` OR `assessments:read:all` OR `assessments:read:team` OR `assessments:edit:assigned`

The `clientId` query parameter disambiguates multiple tabs for the same user (e.g. `clientId=tab1`). The server key for each subscriber is `{username}:{clientId}` (or `{username}:default` if no clientId is provided).

### Connection Lifecycle

```
Browser                                      Server (AssessmentLockService)
  │                                                    │
  ├── GET /assessments/{id}/events ──────────────────► │
  │                                                    ├─ Create SseEmitter (infinite timeout)
  │                                                    ├─ Register emitter under key "{user}:{clientId}"
  │                                                    │
  │ ◄── event: current_locks ───────────────────────── │
  │         data: { locks: [{fieldId, username, ...}] }│  (initial snapshot of all active locks)
  │                                                    │
  │         ... connection held open ...               │
  │                                                    │
  │ ◄── comment: heartbeat ─────────────────────────── │  (every 30 seconds)
  │                                                    │
  │ ◄── event: field_locked ────────────────────────── │  (when any user locks a field)
  │         data: { fieldId, username, displayName }   │
  │                                                    │
  │ ◄── event: field_unlocked ──────────────────────── │  (when lock expires or is released)
  │         data: { fieldId }                          │
  │                                                    │
  │ ◄── event: field_updated ───────────────────────── │  (when another user saves a field)
  │         data: { fieldId, value }                   │
  │                                                    │
  ├── Connection closes (tab closed / navigation) ───► │
       Server catches send failure → removes dead emitter
```

---

## Event Types

### `current_locks`
Sent immediately upon subscription with a snapshot of all currently active locks.

```
event: current_locks
data: {"locks":[{"fieldId":"field-001","username":"jdoe","displayName":"John Doe"}]}
```

### `field_locked`
Broadcast to all subscribers when any user acquires a lock.

```
event: field_locked
data: {"fieldId":"field-001","username":"jdoe","displayName":"John Doe"}
```

### `field_unlocked`
Broadcast when a lock expires (TTL reached) or is explicitly released.

```
event: field_unlocked
data: {"fieldId":"field-001"}
```

### `field_updated`
Broadcast when a user saves a field value. Sent to all subscribers **except** the user who performed the save.

```
event: field_updated
data: {"fieldId":"field-001","value":"## Executive Summary\n\nUpdated content..."}
```

### Heartbeat
A comment-only event (no `event:` line, no `data:` line) sent every 30 seconds to keep the TCP connection alive through reverse proxies.

```
: heartbeat
```

---

## Field Lock Lifecycle

### Acquiring a Lock

When a user focuses a field input, the frontend immediately calls:

```
POST /api/v1/assessments/{id}/fields/{fieldId}/lock
```

**Permission required:** `super_admin` OR `assessments:edit:all` OR `assessments:edit:team` OR `assessments:edit:assigned`

```
AssessmentLockService.acquireLock(assessmentId, fieldId, username, displayName)
  │
  ├─ Get existing lock for this fieldId (if any)
  │
  ├─ No existing lock?
  │     → Create new FieldLock(fieldId, username, displayName, lastActivity=now)
  │     → Broadcast field_locked to all subscribers
  │     → Return 200 OK
  │
  ├─ Lock owned by THIS user?
  │     → refreshActivity() — reset the TTL timer
  │     → Broadcast field_locked (re-announces the lock)
  │     → Return 200 OK
  │
  └─ Lock owned by ANOTHER user and not expired?
        → Return 409 Conflict
              { "locked": true, "lockedBy": "jdoe", "displayName": "John Doe" }
```

The frontend re-acquires the lock on a timer (e.g. every 3 seconds while the field is focused) to keep the `lastActivity` refreshed and prevent the lock from expiring mid-edit.

### Lock Expiry (Automatic)

A scheduler runs every 2 seconds and evicts any locks whose `lastActivity` is more than **5 seconds** ago:

```
@Scheduled(fixedDelay = 2000)
expireStaleLocks()
  │
  └─ For each assessment's lock map:
       └─ For each FieldLock:
            └─ isExpired(5 seconds)?
                 → YES → remove lock from map
                        → Broadcast field_unlocked
```

This means if a user closes their tab or loses connectivity without explicitly releasing the lock, it will auto-expire within ~5–7 seconds.

### Releasing a Lock

When a user blurs a field (clicks away or saves), the frontend calls:

```
DELETE /api/v1/assessments/{id}/fields/{fieldId}/lock
```

```
AssessmentLockService.releaseLock(assessmentId, fieldId, username)
  │
  ├─ Verify lock is owned by this username
  ├─ Remove lock from map
  └─ Broadcast field_unlocked to all subscribers
```

Only the lock owner can release it. Another user cannot forcibly unlock someone else's field (auto-expiry handles that case).

---

## Lock State Diagram

```
Field State Machine
───────────────────
            focus (POST /lock → 200)
  UNLOCKED ──────────────────────────────► LOCKED (by me)
     ▲                                           │
     │  blur (DELETE /lock)                      │ keep alive:
     │  or auto-expire (5s no activity)          │ POST /lock every 3s
     └───────────────────────────────────────────┘

            another user focuses (POST /lock → 409)
  UNLOCKED ──────────────────────────────► LOCKED (by other)
     ▲                                           │
     │  other user blurs (DELETE /lock)          │
     │  or auto-expire                           │
     └───────────────────────────────────────────┘
```

---

## Heartbeat Mechanism

SSE connections are long-lived HTTP responses. Reverse proxies (nginx, Cloudflare, etc.) and some TCP tunnels (e.g. RustDesk) will close idle connections after a timeout. The heartbeat prevents this:

```
@Scheduled(fixedDelay = 30000)   // every 30 seconds
sendHeartbeats()
  │
  └─ For each active SseEmitter:
       ├─ send comment event: ": heartbeat"
       └─ If send throws IOException:
              → mark emitter as dead
              → remove from subscribers map
```

The comment event format (`: heartbeat`) is ignored by EventSource on the client but counts as data on the TCP layer, resetting the proxy's idle timer.

---

## Collaborative Editing Diagram

```
User A (assessor-1)          Server                     User B (assessor-2)
─────────────────────        ──────────────────────     ──────────────────────
Open assessment              Holds SSE connections      Open assessment
  └─ GET /events ──────────► register emitter A         └─ GET /events ──────►  register emitter B
     ◄── current_locks ────── (no locks yet)               ◄── current_locks ──── (no locks yet)

Focus "Executive Summary"
  └─ POST /fields/001/lock ► acquireLock("001", "A")
     ◄── 200 OK              broadcast field_locked ──────► field_locked received
                                                              → field "001" shown as disabled
                                                                with "Locked by User A" tooltip

[User A types content...]    expireStaleLocks() every 2s
  └─ POST /fields/001/lock ► refreshActivity on lock "001"  (field stays locked for B)

Save field
  └─ PUT /assessments/{id}  ► update fieldValues
     ◄── 200 OK              broadcastFieldUpdated("001", newValue, excludeUsername="A")
                                    ──────────────────────► field_updated received
                                                              → Toast UI Editor content updated
                                                                silently for User B

Blur field
  └─ DELETE /fields/001/lock► releaseLock("001", "A")
     ◄── 200 OK              broadcast field_unlocked ─────► field_unlocked received
                                                              → field "001" re-enabled for User B
```

---

## In-Memory State

Locks are stored in-memory on the server (not persisted to MongoDB). This means:

- Locks are lost on server restart — they will auto-expire when clients reconnect.
- There is no distributed locking. With multiple server instances, locks would not be shared across instances. For the current single-instance deployment, this is not an issue.

The in-memory structure is:

```
Map<assessmentId, Map<fieldId, FieldLock>>

FieldLock {
  fieldId:      "field-001"
  username:     "jdoe"
  displayName:  "John Doe"
  lastActivity: Instant  (volatile — updated on every re-acquire)
}
```

SseEmitters are similarly held in-memory:

```
Map<assessmentId, Map<clientKey, SseEmitter>>

clientKey = "{username}:{clientId}"
```

Dead emitters (caused by disconnected clients) are pruned during the heartbeat send cycle and whenever a broadcast fails.
