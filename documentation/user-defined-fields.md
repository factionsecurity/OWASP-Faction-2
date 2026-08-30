# User Defined Fields

## Overview

User Defined Fields (UDFs) allow report templates to carry a configurable set of custom input fields that assessors fill in during an engagement. Because assessments are long-lived documents, the system must handle template changes gracefully — using a snapshot-and-sync model that preserves entered data even as templates evolve.

---

## Field Types

| Type | Description | UI Control |
|------|-------------|-----------|
| `STRING` | Single-line plain text | Text input |
| `RICH_TEXT` | Multi-line Markdown / WYSIWYG editor | Toast UI Editor |
| `DROPDOWN` | Single selection from a predefined list | Select element |

---

## Field Scope

Every field has a `fieldScope` property that determines which part of the application it belongs to. This allows a single template to carry field definitions for multiple contexts.

| Scope | Where Used | Set By |
|-------|-----------|--------|
| `ASSESSMENT` | Assessment Detail page — filled in per engagement | Report Designer "User Defined Fields for Assessments" panel |
| `VULNERABILITY` | Vulnerability records — filled in per finding | Report Designer "User Defined Fields for Vulnerabilities" panel |

`fieldScope` defaults to `ASSESSMENT` if not specified. The two scopes are shown in separate panels in the Report Designer and are managed independently.

---

## Template Definition

Fields are defined in the Report Designer under two panels:

```
Report Template
  ├── Template Information (name, description, assessment type)
  ├── Custom CSS Formatting
  ├── User Defined Fields for Assessments
  │     field 1: variableName="executive_summary", fieldType=RICH_TEXT
  │     field 2: variableName="scope",             fieldType=STRING
  │     field 3: variableName="risk_rating",       fieldType=DROPDOWN
  │                options: ["Critical","High","Medium","Low"]
  └── User Defined Fields for Vulnerabilities
        field 4: variableName="cvss_score",        fieldType=STRING
        field 5: variableName="proof_of_concept",  fieldType=RICH_TEXT
```

Each field has:

| Property | Description |
|----------|-------------|
| `id` | UUID, assigned by the system |
| `variableName` | Snake-case key used in the DOCX template (`${executive_summary}`) — **must be unique within the template** |
| `displayName` | Human-readable label shown to assessors |
| `fieldType` | STRING / RICH_TEXT / DROPDOWN |
| `fieldScope` | ASSESSMENT or VULNERABILITY |
| `dropdownOptions` | List of allowed values (DROPDOWN only) |
| `defaultValue` | Pre-filled value when the assessment is created |
| `helpText` | Guidance text shown under the field |
| `required` | Whether the field must have a value |
| `minLength` / `maxLength` | Constraints for STRING and RICH_TEXT |
| `displayOrder` | Sort order in the UI |

When any field property is changed, the template's `version` integer is incremented. This version number drives the sync mechanism.

---

## Snapshot at Assessment Creation

When an assessment is created, all ASSESSMENT-scoped fields are deep-copied from the live template into `assessment.fieldDefinitions`. This snapshot is independent of the template — changes to the template after creation do not immediately affect the assessment.

### What is copied

```
ReportTemplate.userDefinedFields
  │
  ├── filter: fieldScope == ASSESSMENT (or null)
  │
  └── deep copy each field:
        assessment.fieldDefinitions[0] = {
          id:            "field-snapshot-id-001",   ← same as template ID at creation
          variableName:  "executive_summary",
          displayName:   "Executive Summary",
          fieldType:     "RICH_TEXT",
          fieldScope:    "ASSESSMENT",
          defaultValue:  "",
          ...all other properties
        }
```

VULNERABILITY-scoped fields are **not** included in the assessment snapshot. They are used separately by the vulnerability management subsystem.

### Field Values Storage

Assessors' inputs are stored in `assessment.fieldValues`, a flat map of `fieldId → value string`:

```json
{
  "field-snapshot-id-001": "## Executive Summary\n\nNo critical findings...",
  "field-snapshot-id-002": "External web application",
  "field-snapshot-id-003": "High"
}
```

The keys are the snapshot field IDs (not variable names). This is intentional — the ID is what the sync mechanism preserves as it reconciles template changes.

---

## Field Sync on Load

Because templates can change after assessment creation, the system automatically reconciles the assessment's field snapshot with the live template every time the assessment is fetched (`GET /api/v1/assessments/{id}`).

### Trigger

```java
// AssessmentService.getAssessment()
syncFieldDefinitionsIfNeeded(assessment);
```

The sync is a no-op if `assessment.reportTemplateVersion == template.version`.

### The Stable Key Problem

`fieldValues` are keyed by the field's snapshot **ID**. If a template field is deleted and re-added (getting a new ID), a naive sync would lose any saved value for it, even if the field represents the same concept.

**Solution:** `variableName` is the stable identifier. It is the snake-case key that appears in the DOCX template (`${executive_summary}`) and is expected to remain stable across field edits. The sync algorithm uses it to match existing snapshot fields to their live template counterparts and **preserve the old snapshot ID** on any matched field.

### Sync Algorithm

```
Build lookup:  variableName → existing snapshot ID
  e.g.  { "executive_summary" → "old-id-111",
          "scope"             → "old-id-222" }

For each ASSESSMENT-scoped field in the live template:
  ┌─ variableName exists in lookup?
  │     YES → deep copy field, but set id = old snapshot ID
  │           (fieldValues["old-id-111"] remains valid)
  │     NO  → deep copy field, keep template's new ID
  │           (brand-new field, no prior value)
  └─ add to merged list

Collect validIds = all IDs in merged list

For each key in assessment.fieldValues:
  └─ key not in validIds? → remove entry
       (field was deleted from the template)

Save: assessment.fieldDefinitions = merged
      assessment.reportTemplateVersion = template.version
```

### Sync Diagram

```
BEFORE SYNC
───────────────────────────────────────────────────────────────────
Template v2                          Assessment (snapshot at v1)
────────────────────────             ───────────────────────────────
id: "tmpl-AAA"                       id: "snap-111"   ← fieldValue exists
variableName: "executive_summary"    variableName: "executive_summary"
displayName:  "Executive Summary"    displayName:  "Executive Summary"
  (display name unchanged)

id: "tmpl-BBB"                       id: "snap-222"   ← fieldValue exists
variableName: "scope"                variableName: "scope"
helpText:     "Updated help text"    helpText:     "Old help text"
  (help text changed — new version)

id: "tmpl-CCC"                       (field not yet in snapshot)
variableName: "new_added_field"
  (brand-new field in v2)

                                     id: "snap-333"   ← fieldValue exists
                                     variableName: "removed_field"
                                       (field deleted from template in v2)

AFTER SYNC
───────────────────────────────────────────────────────────────────
Assessment (snapshot at v2)
────────────────────────────────────────────────────────────────────
id: "snap-111"                       ← PRESERVED (variableName matched)
variableName: "executive_summary"
displayName:  "Executive Summary"
  fieldValues["snap-111"] = "## Executive Summary..." ← INTACT

id: "snap-222"                       ← PRESERVED (variableName matched)
variableName: "scope"
helpText:     "Updated help text"    ← UPDATED from live template
  fieldValues["snap-222"] = "External app" ← INTACT

id: "tmpl-CCC"                       ← NEW (template ID kept, no prior value)
variableName: "new_added_field"
  fieldValues["tmpl-CCC"] = (empty)

fieldValues["snap-333"] → DELETED    ← "removed_field" no longer in template
```

---

## Frontend Integration

### Report Designer

The Report Designer presents two independent panels. Each panel's "Add Field" button creates a new field with the correct `fieldScope` pre-set.

```
Report Designer
  │
  ├── [USER DEFINED FIELDS FOR ASSESSMENTS]   [+ Add Field] → fieldScope: ASSESSMENT
  │     ┌──────────────────────────────────────────────────────────────┐
  │     │ ⣿ Display Name  Variable Name  Field Type  [🗑]             │
  │     │   [Input]       [Input]         [Select]                     │
  │     │   Default Value: [Input / Editor / Select]                   │
  │     │   Usage: ${variable_name} [📋]                               │
  │     └──────────────────────────────────────────────────────────────┘
  │
  └── [USER DEFINED FIELDS FOR VULNERABILITIES]  [+ Add Field] → fieldScope: VULNERABILITY
        ┌──────────────────────────────────────────────────────────────┐
        │  (same field row structure as above)                         │
        └──────────────────────────────────────────────────────────────┘
```

All fields (both scopes) are stored in a single `userDefinedFields` array on the template. The two panels are rendered by filtering that array:
- Assessments panel: `fieldScope == 'ASSESSMENT'` or `fieldScope == null`
- Vulnerabilities panel: `fieldScope == 'VULNERABILITY'`

Drag-and-drop reordering is constrained within each section — a field cannot be dragged from one scope panel to the other.

### Assessment Detail Page

The Assessment Detail page renders only the assessment's snapshotted `fieldDefinitions` (already filtered to ASSESSMENT scope during creation). Fields are rendered based on `fieldType`:

| fieldType | Component |
|-----------|-----------|
| `STRING` | Plain `<input>` |
| `DROPDOWN` | `<select>` with options from snapshot |
| `RICH_TEXT` | Toast UI Editor (Markdown/WYSIWYG) |

All field changes are sent via `PUT /api/v1/assessments/{id}` with the `fieldValues` map. The backend validates each value against the current snapshot's constraints before saving.

Fields are **read-only** when the assessment is in a finalized status (`COMPLETED`, `APPROVED`, or `ARCHIVED`).

---

## Template Versioning

Every time a template is updated, the backend checks whether the field definitions have structurally changed. If they have, `version` is incremented:

```
Old template: version = 3, fields = [A, B, C]
              ↓  user adds field D, renames field B
New template: version = 4, fields = [A, B', C, D]

Any assessment with reportTemplateVersion < 4 will be synced on next GET.
```

The version increment is triggered by changes to any field's:
- `variableName`, `displayName`, `fieldType`, `required`, `helpText`, `dropdownOptions`, `defaultValue`, `displayOrder`, or `fieldScope`

CSS changes and template file uploads do **not** increment the version, because they do not affect field definitions.
