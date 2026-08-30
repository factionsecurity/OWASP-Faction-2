# API Keys

## Overview

Alongside interactive **JWT** login, the API supports **opaque API keys** for programmatic access (CI pipelines, integrations, service accounts). A key is presented exactly like a JWT — `Authorization: Bearer <token>` — but carries no claims of its own. Its authorities are resolved **live on every request**, which is the whole reason keys exist: a long-lived JWT bakes the caller's authorities in at issue time and drifts out of step with reality, whereas an API key always reflects current permissions.

An API key is simply a **second way to populate the same `Authentication`** in the security context. Every `@PreAuthorize` and every service-layer scope check reads that `Authentication` and works unchanged — the authorization model is untouched; only authentication gains a credential type.

---

## Key Format & Transport

```
Authorization: Bearer sk_fac_<base64url(32 random bytes)>
```

- The `sk_fac_` prefix (**s**ecret **k**ey, **fac**tion-namespaced) distinguishes keys from JWTs (which always start with `eyJ`) and enables secret-scanning / leak attribution.
- 32 bytes of `SecureRandom` entropy, base64url-encoded without padding.
- The **full plaintext is shown exactly once**, at creation. Only a hash is ever stored (see [Storage & Hashing](#storage--hashing)); it cannot be recovered.

The [`ApiKeyAuthenticationFilter`](#component-map) runs **before** the JWT filter. If the bearer value starts with `sk_fac_` it authenticates as an API key; otherwise it leaves the request for the JWT filter. Both filters build the identical `UsernamePasswordAuthenticationToken` shape, so everything downstream is agnostic to how the request authenticated.

---

## Key Types

There are two kinds of key, distinguished by an explicit `key_type` column.

| | **User key** | **System key** |
|---|---|---|
| Owner | A specific internal user (`user_id`) | None (ownerless service account) |
| Principal | The owner's username | `system:<key name>` |
| Authorities | Derived **live** from the owner (see [Scope](#scope-model)) | The key's own **stored** permission list |
| Scope | `READ_WRITE` or `READ_ONLY` | Always `CUSTOM` |
| Who can create | The user themselves (self-service) | `super_admin` only |
| Management UX | Self-service REST | Backend / admin REST (no end-user UX) |

A **type/scope invariant** is enforced in `ApiKeyService` (the only writer): `USER` keys are `READ_WRITE` or `READ_ONLY`; `SYSTEM` keys are always `CUSTOM`. A user key carrying `CUSTOM` (only reachable by direct DB manipulation) is **refused at authentication**.

---

## Scope Model

`ApiKeyScope` decides how a key's effective authorities are computed at authentication time.

| Scope | Applies to | Effective authorities |
|-------|-----------|-----------------------|
| `READ_WRITE` | User keys (default) | The owner's live permissions, unfiltered |
| `READ_ONLY` | User keys | The owner's live permissions, filtered to **read actions** |
| `CUSTOM` | System keys | Exactly the permissions stored on the key |

The two user-key scopes are **predicates over the owner's live permissions**, evaluated fresh on every request — nothing is snapshotted:

- Owner loses a permission → the key loses it **immediately**.
- Owner gains a permission → a `READ_WRITE` key gains it; a `READ_ONLY` key gains it too if it is a read action.

This is why user keys never store a permission list and can never go stale.

### READ_ONLY filtering

"Read action" is classified from the middle segment of `resource:action:scope`:

- **Read-ish** (kept): `read`, `view`, `download`.
- **Mutating** (dropped): everything else — `create`, `edit`, `write`, `delete`, `retest`, `comment`, `complete`, `import`, … **and any unknown/future action** (deny-by-default, so a newly added action can never silently leak into read-only keys).

**`super_admin` expansion.** `super_admin` is a wildcard, not a member of the permission set. For a `READ_ONLY` key whose owner is a super admin, the wildcard is first expanded to the full `Permission` universe, then filtered — so the key holds **every known read permission but never `super_admin` itself**. Deliberate consequence: endpoints gated `super_admin`-only (e.g. `GET /api/v1/roles`) refuse an admin's read-only key. A de-fanged admin key should not reach admin-only surfaces.

> The read universe is only as complete as the `Permission` enum. Permission strings that appear in `@PreAuthorize` gates but are missing from the enum are invisible to the expansion. Keeping the enum authoritative is tracked as a separate hygiene effort.

---

## Authority Resolution (per request)

`ApiKeyService.authenticate(rawKey)` returns the principal + authorities, or empty (→ 403). Order:

1. Reject if the value lacks the `sk_fac_` prefix.
2. Look up by `SHA-256(rawKey)`; reject if unknown, revoked, or (reserved) expired.
3. **User key:** load the owner; reject if the owner is missing, deleted, disabled, or **not internal** (external/portal users can neither create nor authenticate with a key). Then derive authorities from the owner's live permissions per the key's scope. A `CUSTOM` user key is refused here.
4. **System key:** principal is `system:<name>`; authorities are the key's stored list (an empty list yields an **inert** key — it authenticates but is authorized for nothing).
5. Stamp `lastUsedAt` (throttled to ~60s to avoid a write per request) and the key id onto a request attribute for audit.

---

## Authorization: who can manage keys

### Permission taxonomy

Five permissions, all under the `API Keys` resource:

| Permission | Grants |
|-----------|--------|
| `apikeys:create:self` | Create your own API keys |
| `apikeys:read:self` | List your own API keys |
| `apikeys:delete:self` | Revoke your own API keys |
| `apikeys:read:system` | List system keys |
| `apikeys:delete:system` | Revoke system keys |

There are deliberately **no unscoped (`apikeys:create` / `read` / …) permissions** — an unscoped grant would imply a cross-user management surface that does not exist (every user-key endpoint is self-scoped), and would be a second path to escalation. There is likewise **no `apikeys:*:system` create or edit permission**: minting or re-scoping a system key is an unbounded permission grant, so it is `super_admin`-only (the same bar the codebase applies to role management). Only the non-escalating system verbs — list and revoke — are delegable.

### Endpoint gating

| Endpoint | Method | Gate | Caller check |
|----------|--------|------|--------------|
| Create your key | `POST /api/v1/api-keys` | `super_admin` OR `apikeys:create:self` | internal user |
| List your keys | `GET /api/v1/api-keys` | `super_admin` OR `apikeys:read:self` | internal user |
| Revoke your key | `DELETE /api/v1/api-keys/{id}` | `super_admin` OR `apikeys:delete:self` | internal user |
| Create system key | `POST /api/v1/api-keys/system` | `super_admin` | not external |
| List system keys | `GET /api/v1/api-keys/system` | `super_admin` OR `apikeys:read:system` | not external |
| Update system key | `PUT /api/v1/api-keys/system/{id}` | `super_admin` | not external |
| Revoke system key | `DELETE /api/v1/api-keys/system/{id}` | `super_admin` OR `apikeys:delete:system` | not external |

**Caller checks** (beyond the permission gate):

- **User-key endpoints** require the caller to resolve to a real **internal user** — the check also *is* the owner resolution. External users and non-user principals (e.g. a system key, which has nothing to own here) are rejected.
- **System-key endpoints** require only **"not an external user."** Internal users pass, and so do principals that resolve to no user at all — i.e. system-key principals. This lets a system key granted a delegable `:system` verb actually use it (e.g. a security-automation service account that lists and revokes compromised keys). `@PreAuthorize` remains the real permission gate.

### No user-key edit

User keys are **not editable**. Their only mutable state is a binary scope and a cosmetic name, and re-scoping a live credential in place would silently change what a fixed secret can do. Changing a user key's posture goes through **revoke + create**, which yields a fresh secret (auditable, and fail-closed for holders of the old one). System keys *are* editable (`PUT …/system/{id}`) because they carry a rich, admin-assigned permission list and live in infrastructure where forcing a secret rotation per tweak would be wrong.

---

## The escalation invariant

The whole model upholds one property:

> **The API-key mechanism can never confer a permission its creator does not already hold.**

- **User keys** satisfy it structurally: a scope is a filter over the owner's live permissions, and a filter can only pass through or subtract. A key is always ≤ its owner, live.
- **System keys** satisfy it because only a `super_admin` — who already holds everything — may create one or assign its permissions.

A super_admin system key *can* create further system keys. This is accepted equivalent-power: such a key can already do anything through every other controller (none of which check the caller's identity), so blocking it only here would be theater, not defense.

---

## Storage & Hashing

Persisted in the `api_keys` table (JPA; created by Hibernate `ddl-auto`).

| Column | Notes |
|--------|-------|
| `token_hash` | `SHA-256(fullKey)`, base64url, **unique** + indexed. The only stored representation of the secret. |
| `hint` | Non-secret display fragment: `sk_fac_` + the first 6 random chars (e.g. `sk_fac_ab12cd`). Lets users tell keys apart; never used for auth. |
| `key_type` | `USER` \| `SYSTEM` (indexed). |
| `scope` | `READ_WRITE` \| `READ_ONLY` \| `CUSTOM`. |
| `user_id` | Owner for user keys; null for system keys (indexed). |
| `permissions` | JSON list; meaningful only for `CUSTOM` (system) keys. |
| `created_at` / `expires_at` / `revoked_at` / `last_used_at` | `Instant` (UTC). `expires_at` is reserved: authentication enforces it *if set*, but nothing sets it and it is **not exposed in the API response** — so it is effectively inert until expiration becomes a real feature. |

**SHA-256, not BCrypt:** the token is 256 bits of random entropy, so a slow password hash would add only latency to a per-request lookup. If the scheme ever needs to change, add a `hash_version` column defaulting existing rows to the current scheme and verify lazily (the plaintext is gone, so old keys must keep verifying under their original hash).

---

## Name uniqueness

A key's name must be unique among **active** (non-revoked) keys in its scope — per owner for user keys, globally for system keys — compared case-insensitively (and trimmed). A duplicate is rejected with `400`. This matters most for system keys: their principal is `system:<name>`, so two active keys with the same name would be indistinguishable in audit logs. Once a key is revoked its name is free to reuse.

## Lifecycle

- **Create** → the plaintext is returned once; store it immediately.
- **Use** → present as `Authorization: Bearer sk_fac_…`; `last_used_at` is updated (throttled).
- **Revoke** → sets `revoked_at`; the key is rejected immediately on the next request. Revocation is the only lifecycle control (expiration is deferred).

---

## Reserved username prefix

The `system:` username prefix is **reserved** (`UserService` rejects it on create/update). A system key's synthetic principal is `system:<name>`, so a real user named `system:…` could otherwise collide with — and be mistaken for — a system-key principal. Colons elsewhere in a username are fine.

---

## REST Reference

All responses use the standard `JsonApiResponse` envelope (`{ success, message, data }`).

### Create a user key

```
POST /api/v1/api-keys
{ "name": "ci pipeline", "scope": "READ_ONLY" }   // scope optional; defaults to READ_WRITE
```

```json
{
  "success": true,
  "message": "API key created successfully",
  "data": {
    "key": "sk_fac_XXXXXXXX…",                 // shown once, never stored
    "apiKey": {
      "id": "…", "name": "ci pipeline", "keyType": "USER",
      "scope": "READ_ONLY", "hint": "sk_fac_XXXXXX",
      "permissions": [], "createdAt": "…", "lastUsedAt": null
    }
  }
}
```

### Create a system key

```
POST /api/v1/api-keys/system            (super_admin)
{ "name": "nightly-sync", "permissions": ["assessments:read:all", "vulnerabilities:read:all"] }
```

### Other operations

```
GET    /api/v1/api-keys                  list your active keys (metadata only; never the secret)
DELETE /api/v1/api-keys/{id}             revoke your key
GET    /api/v1/api-keys/system           list system keys
PUT    /api/v1/api-keys/system/{id}      rename / re-scope a system key
DELETE /api/v1/api-keys/system/{id}      revoke a system key
```

The `ApiKeyDto` returned by list/read endpoints never includes the plaintext or the hash — only non-secret metadata and the `hint`.

### Example: authenticate a request with a key

```bash
curl https://host/api/v1/auth/me \
  -H "Authorization: Bearer sk_fac_XXXXXXXX…"
```

---

## Component Map

| Concern | Class |
|---------|-------|
| Entity / enums | `model/ApiKey`, `model/ApiKeyType`, `model/ApiKeyScope` |
| Persistence | `repository/ApiKeyRepository` |
| Generation, authentication, resolution, management | `service/ApiKeyService` |
| Read-action classifier + read universe | `model/Permission` (`isReadAction`, `allReadPermissions`) |
| Authentication filter | `security/ApiKeyAuthenticationFilter` |
| REST surface | `controller/v1/ApiKeyController` |
| DTOs | `dto/ApiKeyDto`, `CreateApiKeyRequest`, `CreateApiKeyResponse`, `CreateSystemApiKeyRequest`, `UpdateSystemApiKeyRequest` |
| Reserved-prefix guard | `service/UserService` |

See also: [Permissions Reference](./permissions.md).
