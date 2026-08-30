# Permissions Reference

## Overview

All API endpoints are protected by JWT authentication followed by method-level authorization using `@PreAuthorize`. The authorization system uses a custom `PermissionEvaluator` that checks whether the authenticated user's roles contain the required permission string.

The special permission `super_admin` bypasses all other checks — a user with this permission has full access to everything.

---

## Permission Format

Permissions follow the pattern:

```
{resource}:{action}:{scope}
```

| Component | Examples |
|-----------|---------|
| resource | `assessments`, `applications`, `organizations`, `users`, `report_templates` |
| action | `read`, `create`, `edit`, `write`, `delete` |
| scope | `all`, `team`, `assigned`, `self` |

A user may have multiple permissions. The `@PreAuthorize` expressions use `OR` logic — any one matching permission grants access.

---

## Permission Map by Service

### Organizations

| Endpoint | Method | Required Permission |
|----------|--------|-------------------|
| List all organizations | `GET /api/v1/organizations` | `super_admin` OR `organizations:read:all` |
| Get organization by ID | `GET /api/v1/organizations/{id}` | `super_admin` OR `organizations:read:all` |
| Create organization | `POST /api/v1/organizations` | `super_admin` OR `organizations:create:all` |
| Update organization | `PUT /api/v1/organizations/{id}` | `super_admin` OR `organizations:edit:all` |
| Delete organization | `DELETE /api/v1/organizations/{id}` | `super_admin` OR `organizations:delete:all` |

### Applications

| Endpoint | Method | Required Permission |
|----------|--------|-------------------|
| List all applications | `GET /api/v1/applications` | `super_admin` OR `applications:read:all` |
| Get application by ID | `GET /api/v1/applications/{id}` | `super_admin` OR `applications:read:all` |
| List by organization | `GET /api/v1/applications/organization/{orgId}` | `super_admin` OR `applications:read:all` OR `applications:read:org` |
| Create application | `POST /api/v1/applications` | `super_admin` OR `applications:create:all` |
| Update application | `PUT /api/v1/applications/{id}` | `super_admin` OR `applications:edit:all` |
| Move to organization | `PUT /api/v1/applications/{id}/move/{orgId}` | `super_admin` only |
| Delete application | `DELETE /api/v1/applications/{id}` | `super_admin` OR `applications:delete:all` |

### Assessments

| Endpoint | Method | Required Permission |
|----------|--------|-------------------|
| Create assessment | `POST /api/v1/assessments` | `super_admin` OR `assessments:create:all` |
| List / search assessments | `GET /api/v1/assessments` | `super_admin` OR `assessments:read:all` OR `assessments:read:team` OR `assessments:read:assigned` |
| Get assessment by ID | `GET /api/v1/assessments/{id}` | `super_admin` OR `assessments:read:all` OR `assessments:read:team` OR `assessments:read:assigned` |
| Update assessment | `PUT /api/v1/assessments/{id}` | `super_admin` OR `assessments:edit:all` OR `assessments:edit:team` OR `assessments:edit:assigned` |
| Delete assessment | `DELETE /api/v1/assessments/{id}` | `super_admin` OR `assessments:delete:all` |
| Get by application | `GET /api/v1/assessments/by-application/{appId}` | `super_admin` OR `assessments:read:all` |
| Validate field values | `POST /api/v1/assessments/{id}/validate` | `super_admin` OR `assessments:edit:all` |
| Get metrics | `GET /api/v1/assessments/metrics` | `super_admin` OR `assessments:read:all` |
| Calendar view | `GET /api/v1/assessments/calendar` | `super_admin` OR `assessments:read:all` |
| Check conflicts | `POST /api/v1/assessments/check-conflicts` | `super_admin` OR `assessments:create:all` |
| Export CSV | `GET /api/v1/assessments/export/csv` | `super_admin` OR `assessments:read:all` |
| Subscribe to SSE events | `GET /api/v1/assessments/{id}/events` | `super_admin` OR `assessments:read:all` OR `assessments:read:team` OR `assessments:edit:assigned` |
| Acquire field lock | `POST /api/v1/assessments/{id}/fields/{fieldId}/lock` | `super_admin` OR `assessments:edit:all` OR `assessments:edit:team` OR `assessments:edit:assigned` |
| Release field lock | `DELETE /api/v1/assessments/{id}/fields/{fieldId}/lock` | `super_admin` OR `assessments:edit:all` OR `assessments:edit:team` OR `assessments:edit:assigned` |

#### Assessment File Attachments

| Endpoint | Method | Required Permission |
|----------|--------|-------------------|
| Allocate upload target | `POST /api/v1/assessments/{id}/files/prepare` | File Write Auth (see below) |
| Upload file bytes | `PUT /api/v1/assessments/{id}/files/{fileId}/content` | File Write Auth |
| Confirm upload | `POST /api/v1/assessments/{id}/files` | File Write Auth |
| Download file | `GET /api/v1/assessments/{id}/files/{fileId}/content` | File Read Auth |
| Delete file | `DELETE /api/v1/assessments/{id}/files/{fileId}` | File Write Auth |

**File Write Auth:** `super_admin` OR `assessments:write:all` OR `assessments:write:team` OR `assessments:write:assigned` OR `assessments:edit:all` OR `assessments:edit:team` OR `assessments:edit:assigned`

**File Read Auth:** `super_admin` OR `assessments:read:all` OR `assessments:read:team` OR `assessments:read:assigned`

### Report Templates (User Defined Fields)

User Defined Fields are defined inside Report Templates. There is no separate UDF endpoint — they are managed as part of `PUT /api/v1/report-templates/{id}`.

| Endpoint | Method | Required Permission |
|----------|--------|-------------------|
| Create template | `POST /api/v1/report-templates` | `super_admin` OR `report_templates:create:all` |
| List templates | `GET /api/v1/report-templates` | `super_admin` OR `report_templates:read:all` |
| Get template by ID | `GET /api/v1/report-templates/{id}` | `super_admin` OR `report_templates:read:all` |
| Update template (incl. UDF edits) | `PUT /api/v1/report-templates/{id}` | `super_admin` OR `report_templates:edit:all` |
| Delete template | `DELETE /api/v1/report-templates/{id}` | `super_admin` OR `report_templates:delete:all` |
| Upload DOCX file | `POST /api/v1/report-templates/{id}/file` | `super_admin` OR `report_templates:edit:all` |
| Download DOCX file | `GET /api/v1/report-templates/{id}/file` | `super_admin` OR `report_templates:read:all` |
| List by assessment type | `GET /api/v1/report-templates/by-assessment-type/{typeId}` | `super_admin` OR `report_templates:read:all` |

### Inline Images

| Endpoint | Method | Required Permission |
|----------|--------|-------------------|
| Upload inline image | `POST /api/v1/assessments/{assessmentId}/inline-images` | File Write Auth (same as file attachments) |
| Serve inline image | `GET /api/v1/inline-images/{imageId}` | **None (public)** — security via unguessable UUID |

### Users

| Endpoint | Method | Required Permission |
|----------|--------|-------------------|
| List all users | `GET /api/v1/users` | `super_admin` OR `users:read:all` OR `users:read:team` |
| Get user by ID | `GET /api/v1/users/{id}` | `super_admin` OR `users:read:all` OR `users:read:team` |
| Create user | `POST /api/v1/users` | `super_admin` OR `users:create:all` OR `users:create:team` |
| Update user | `PUT /api/v1/users/{id}` | `super_admin` OR `users:edit:all` OR `users:edit:team` |
| Delete user | `DELETE /api/v1/users/{id}` | `super_admin` OR `users:delete:all` OR `users:delete:team` |

### API Keys

Programmatic API-key authentication. Creating or re-scoping a **system** key is an unbounded permission grant, so those verbs are `super_admin`-only; the non-escalating system verbs (list, revoke) are delegable. There are no unscoped `apikeys:*` permissions. See [API Keys](./api-keys.md) for the full design.

| Endpoint | Method | Required Permission |
|----------|--------|-------------------|
| Create your key | `POST /api/v1/api-keys` | `super_admin` OR `apikeys:create:self` |
| List your keys | `GET /api/v1/api-keys` | `super_admin` OR `apikeys:read:self` |
| Revoke your key | `DELETE /api/v1/api-keys/{id}` | `super_admin` OR `apikeys:delete:self` |
| Create system key | `POST /api/v1/api-keys/system` | `super_admin` only |
| List system keys | `GET /api/v1/api-keys/system` | `super_admin` OR `apikeys:read:system` |
| Update system key | `PUT /api/v1/api-keys/system/{id}` | `super_admin` only |
| Revoke system key | `DELETE /api/v1/api-keys/system/{id}` | `super_admin` OR `apikeys:delete:system` |

> User-key endpoints additionally require the caller to be an **internal** user; system-key endpoints require only that the caller is **not external** (so a system key holding a delegable `:system` verb can call them). User keys are intentionally not editable — revoke and re-create instead.

---

## Permission Scope Semantics

| Scope | Meaning |
|-------|---------|
| `all` | Access every record in the system regardless of ownership |
| `team` | Access only records belonging to the user's team(s) |
| `assigned` | Access only assessments the user is directly assigned to (as assessor, engagement manager, or remediation manager) |
| `self` | Access only the user's own record (for API keys: your own keys) |
| `system` | Manage ownerless system (service-account) API keys — see [API Keys](./api-keys.md) |

---

## Default Roles

The system bootstraps two default roles on first startup:

### SuperAdmin

- Permission: `super_admin`
- Default credentials: `admin` / `admin123`
- Has unrestricted access to all endpoints

### Pentester

Has 19 permissions covering the full assessment workflow:

```
assessments:create:all        assessments:read:all
assessments:read:team         assessments:read:assigned
assessments:edit:all          assessments:edit:team
assessments:edit:assigned     assessments:write:all
assessments:write:team        assessments:write:assigned
assessments:delete:all

applications:read:all         organizations:read:all

report_templates:read:all

users:read:all                users:read:team
users:edit:self

vulnerabilities:read:all      vulnerabilities:create:all
```

---

## Permission Check Flow

```
HTTP Request (with Authorization: Bearer <jwt>)
  │
  ▼
JwtAuthenticationFilter
  ├─ Validate JWT signature and expiry
  ├─ Extract username and authorities (permission strings)
  └─ Set SecurityContext with authenticated principal

  ▼
Spring Security Filter Chain
  ├─ Check endpoint is not in permitAll list
  │   (only GET /api/v1/inline-images/{id} and auth endpoints are public)
  │
  └─ All other endpoints require valid JWT → 401 if missing/invalid

  ▼
Controller Method (@PreAuthorize)
  ├─ CustomPermissionEvaluator.hasPermission(auth, permission)
  ├─ Checks authorities list for "super_admin" → grants all
  ├─ Checks authorities list for required permission string
  └─ Returns 403 Forbidden if no match
```
