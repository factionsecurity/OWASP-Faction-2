# Assessment Lifecycle

## Overview

An assessment represents a single security engagement against an application. It is always created from a **Report Template**, which defines the document structure and the custom fields that assessors fill in during the engagement. The template details are snapshotted at creation time so that later changes to the template do not affect in-progress assessments.

---

## Creation Flow

### Prerequisites

Before an assessment can be created, three entities must already exist:

| Entity | Purpose |
|--------|---------|
| **Organization** | The client company owning the application |
| **Application** | The system being assessed |
| **Assessment Type** | Category of assessment (e.g. "Penetration Test") |
| **Report Template** | Defines fields and the DOCX report file; must be `active = true` and linked to the same Assessment Type |

### Validation Steps

When `POST /api/v1/assessments` is called, `AssessmentService.createAssessment()` performs these checks in order:

```
1.  Does the Application exist?              → 404 if not
2.  Does the Assessment Type exist?          → 404 if not
3.  Does the Report Template exist?          → 404 if not
4.  Is the Template active?                  → 400 if inactive
5.  Does Template.assessmentTypeId match
    the requested assessmentTypeId?          → 400 if mismatch
6.  Validate any provided initialFieldValues → 400 on constraint violation
```

### What Gets Copied from the Template

The following data is captured at assessment creation time and stored on the assessment document. This is a **point-in-time snapshot** — subsequent template edits do not automatically affect existing assessments (see [Field Sync](#field-sync)).

| Assessment Field | Source |
|-----------------|--------|
| `fieldDefinitions` | All ASSESSMENT-scoped `userDefinedFields` from the template (deep copy) |
| `reportTemplateVersion` | `template.version` at creation time |
| `templateName` | `template.name` (preserved in case template is later deleted) |
| `templateCss` | `template.css` (CSS used for report generation) |
| `templateFileId` | MinIO storage key of the DOCX template file |
| `reportTemplateId` | Reference to the template document |

Only fields with `fieldScope == ASSESSMENT` (or `null`, which defaults to ASSESSMENT) are included in the snapshot. `VULNERABILITY`-scoped fields are excluded.

### Creation Flow Diagram

```
Client
  │
  ├─ POST /api/v1/assessments
  │     {name, applicationId, assessmentTypeId,
  │      reportTemplateId, assessorIds, ...}
  │
  ▼
AssessmentController
  │
  ├─ @PreAuthorize("assessments:create:all")
  │
  ▼
AssessmentService.createAssessment()
  │
  ├─ 1. Fetch Application           ─── Not found → 404
  ├─ 2. Fetch AssessmentType        ─── Not found → 404
  ├─ 3. Fetch ReportTemplate        ─── Not found → 404
  ├─ 4. Check template.active       ─── false    → 400
  ├─ 5. Check type ID match         ─── mismatch → 400
  ├─ 6. Filter ASSESSMENT fields    ─── fieldScope != ASSESSMENT → excluded
  ├─ 7. Validate initialFieldValues ─── invalid  → 400
  ├─ 8. Copy stakeholders from App  ─── (if none provided in request)
  │
  ├─ Build Assessment document:
  │     status            = DRAFT
  │     fieldDefinitions  = snapshot of ASSESSMENT-scoped fields
  │     reportTemplateVersion = template.version
  │     templateName      = template.name
  │     templateCss       = template.css
  │     templateFileId    = template.templateFileId
  │     assessmentDate    = now()
  │
  ├─ assessmentRepository.save()
  │
  └─ Return AssessmentDto (enriched with display names)
```

---

## Status Workflow

Assessments move through the following statuses:

```
  ┌────────┐
  │ DRAFT  │──────────────────────────────────────────────────────┐
  └────┬───┘                                                      │
       │ start work                                               │
  ┌────▼──────────┐                                              │
  │  IN_PROGRESS  │──────────────────────────────────────────┐   │
  └────┬──────────┘                                          │   │
       │ pause                                               │   │
  ┌────▼─────────┐                                          │   │
  │   ON_HOLD    │                                          │   │
  └────┬─────────┘                                          │   │
       │ resume                                             │   │
  ┌────▼──────────────┐                                    │   │
  │  PENDING_REVIEW   │                                    │   │
  └────┬──────────────┘                                    │   │
       │ approve                                           │   │
  ┌────▼───────────┐   reject                             │   │
  │   COMPLETED    │──────────────────────────────────────┘   │
  └────┬───────────┘                                          │
       │ sign off                                             │ delete
  ┌────▼──────────┐                                          │
  │   APPROVED    │                                          │
  └────┬──────────┘                                          │
       │ archive                                             │
  ┌────▼──────────┐                                          │
  │   ARCHIVED    │ ◄────────────────────────────────────────┘
  └───────────────┘         (soft delete sets deletedAt)
```

Finalized statuses (`COMPLETED`, `APPROVED`, `ARCHIVED`) cause the frontend to render all fields as **read-only** with an amber banner.

---

## User Assignment

Assessments support three distinct user roles:

| Field | Type | Description |
|-------|------|-------------|
| `assessorIds` | `List<String>` | Users performing the assessment work |
| `engagementManagerId` | `String` | Manager overseeing the engagement |
| `remediationManagerId` | `String` | Manager overseeing remediation follow-up |

### Assignment Rules

- All three are optional at creation and can be updated later.
- `assessorIds` is a list — multiple assessors can collaborate.
- A legacy `assessorId` (single string) field is still accepted for backwards compatibility. If provided without `assessorIds`, it is automatically migrated into a single-item `assessorIds` list.
- Stakeholders (client contacts) are copied automatically from the Application's `stakeHolders` list if no stakeholders are supplied in the creation request.

### Assignment Diagram

```
CreateAssessmentRequest
  │
  ├── assessorId (legacy)  ─┐
  ├── assessorIds          ─┴─► Assessment.assessorIds   (List<String>)
  ├── engagementManagerId  ────► Assessment.engagementManagerId
  └── remediationManagerId ────► Assessment.remediationManagerId

On read (GET assessment):
  assessorIds ──► UserRepository.findById() ──► assessorNames[]
                                            └──► assessorEmails[]
```

Display names and emails are resolved from the User collection at read time and included in the DTO. They are not stored on the assessment to avoid staleness.

---

## Field Sync

When a template is updated after an assessment has been created, the assessment's snapshotted field definitions can become stale. The sync mechanism reconciles this automatically the next time the assessment is fetched.

### Sync Trigger

`syncFieldDefinitionsIfNeeded()` is called inside `getAssessment()`. If the template version stored on the assessment is lower than the template's current version, the sync runs.

### Sync Algorithm

The key challenge is that `fieldValues` are stored as `Map<fieldId → value>`. If a field is replaced with a new one (getting a new ID), its stored value would be lost. The solution is to use `variableName` as a stable key.

```
1. Build map: variableName → existing snapshot ID
      e.g. { "executive_summary" → "old-field-id-123" }

2. For each ASSESSMENT-scoped field in the live template:
     a. Deep-copy the field
     b. If variableName exists in the snapshot map:
          → Replace the copy's ID with the old snapshot ID
            (this preserves the fieldValues entry)
     c. If variableName is NEW:
          → Keep the template's ID
            (no existing fieldValue to preserve)

3. Compute the set of valid IDs from the merged list

4. Remove any fieldValues whose key is not in validIds
      (cleans up values for fields deleted from the template)

5. Save: assessment.fieldDefinitions = merged
         assessment.reportTemplateVersion = templateVersion
```

### Field Sync Diagram

```
Template (v2)                  Assessment (snapshot at v1)
─────────────────────          ──────────────────────────────
field: "executive_summary"     field: "executive_summary"
  id: "new-id-AAA"               id: "old-id-111"  ← has fieldValue
  variableName: "exec_sum"       variableName: "exec_sum"

field: "scope_limitations"     field: "scope_limitations"
  id: "new-id-BBB"               id: "old-id-222"  ← has fieldValue
  variableName: "scope_lim"      variableName: "scope_lim"

field: "new_field_added"       (does not exist in v1 snapshot)
  id: "new-id-CCC"
  variableName: "new_field"

                               field: "removed_field"
                                 id: "old-id-333"  ← fieldValue to be deleted
                                 variableName: "removed"

                    ▼  After sync  ▼

Assessment (snapshot at v2)
──────────────────────────────────────────
field: "executive_summary"
  id: "old-id-111"    ← PRESERVED (variableName match)
  variableName: "exec_sum"

field: "scope_limitations"
  id: "old-id-222"    ← PRESERVED (variableName match)
  variableName: "scope_lim"

field: "new_field_added"
  id: "new-id-CCC"    ← New ID (no prior value to preserve)
  variableName: "new_field"

fieldValues["old-id-333"] → DELETED (field no longer in template)
```

---

## Request & Response Shapes

### CreateAssessmentRequest

```json
{
  "name": "Q1 2025 Penetration Test",
  "applicationId": "app-abc123",
  "assessmentTypeId": "type-xyz789",
  "reportTemplateId": "tmpl-def456",
  "assessorIds": ["user-111", "user-222"],
  "engagementManagerId": "user-333",
  "remediationManagerId": "user-444",
  "startDate": "2025-03-01T09:00:00",
  "plannedEndDate": "2025-03-15T17:00:00",
  "scope": "External network perimeter, web application",
  "engagementUrls": [
    { "url": "https://target.example.com", "description": "Primary target" }
  ],
  "stakeholders": [
    { "name": "Alice Smith", "email": "alice@client.com", "role": "CISO" }
  ],
  "initialFieldValues": {}
}
```

### AssessmentDto (abbreviated)

```json
{
  "id": "assessment-ghi789",
  "name": "Q1 2025 Penetration Test",
  "status": "DRAFT",
  "reportTemplateVersion": 3,
  "fieldDefinitions": [
    {
      "id": "field-snapshot-id-001",
      "variableName": "executive_summary",
      "displayName": "Executive Summary",
      "fieldType": "RICH_TEXT",
      "fieldScope": "ASSESSMENT"
    }
  ],
  "fieldValues": {
    "field-snapshot-id-001": "## Summary\nNo critical findings..."
  },
  "assessorIds": ["user-111", "user-222"],
  "assessorNames": ["John Doe", "Jane Smith"],
  "isPastDue": false
}
```
