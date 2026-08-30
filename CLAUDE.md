# CLAUDE.md

This file provides standing instructions for Claude Code when working on this repository.

---

## Page Layout

**Every routed page must wrap its content in the `Page` component** (`frontend/src/components/Page.tsx`). This is the single, reusable layout contract for everything rendered inside the dashboard content area — it guarantees consistent full-width sizing so new features never need bespoke width CSS.

```tsx
import Page from '../components/Page';

// Standard full-width page (tables, lists, dashboards) — the default
<Page>
  <div className="page-header">…</div>
  <DataTable … />
</Page>
```

Variants:
- **default** (omit `variant`) — fills the content area edge-to-edge (full width). Use for all tables, lists, and general content.
- `variant="narrow"` — constrained, **centered** column (`--page-narrow-width`, default 880px). Use only for focused forms and settings pages. Centered, so it never leaves a one-sided gap on the right.
- `variant="flush"` — edge-to-edge; cancels the content-area padding. Use for full-bleed editors / canvases (e.g. the report designer).
- `fill` (boolean) — stretch to the full height of the content area for split / scroll-managed layouts.

Rules:
- **Do not** set `max-width`, outer `margin`, or page padding on a page's own root wrapper — that's what caused inconsistent right-side gaps. Width/centering is owned by `Page`.
- **Do not** wrap nested tabs/sections (e.g. the assessment detail sub-sections) in `Page` — only the top-level routed page gets one.
- Horizontal page padding comes from `.content-area` via the `--content-padding` token — don't re-add it per page.

---

## Rich Text Editors

**Every rich text editor in the frontend must use the `RichTextEditor` component** (`frontend/src/components/RichTextEditor.tsx`). `@toast-ui/react-editor` and the old `MarkdownEditor` wrapper are retired — do not use them.

```tsx
<RichTextEditor
  value={html}
  onChange={(html) => setValue(html)}
/>
```

Key props:
- `value` — HTML string (the editor stores and returns HTML, not markdown)
- `onChange` — called with the updated HTML string on every edit
- `onImageUpload` — `(file: File) => Promise<string>` — pass wherever image upload is needed (use `inlineImagesApi.upload`)
- `disabled` — renders a read-only view; omit or pass `false` for editable mode
- `placeholder` — placeholder text shown when empty

Do **not** pass `height`, `initialEditType`, or `previewStyle` — those were Toast UI props and do not exist on `RichTextEditor`.

---

## Git Commits

- **Never add `Co-Authored-By` lines** to commit messages

---

## Confirmation Dialogs

**Always use the `ConfirmDialog` component** (`frontend/src/components/ConfirmDialog.tsx`) for destructive actions (deletes, overwrites, etc.). Never use `window.confirm()`.

```tsx
<ConfirmDialog
  isOpen={!!confirmId}
  onClose={() => setConfirmId(null)}
  onConfirm={handleConfirmedAction}
  title="Delete Item"
  message="Are you sure? This cannot be undone."
  confirmText="Delete"
  variant="danger"
  isLoading={deleting}
/>
```

- Use `variant="danger"` for deletes, `variant="warning"` for destructive-but-reversible actions, `variant="info"` for non-destructive confirmations
- Track loading state with a dedicated boolean (e.g. `deleting`) and pass it as `isLoading`

---

## Backend Testing

**Every backend feature addition or change requires a test.** A feature is not complete until tests are written and passing.

- Write tests in the appropriate test class under `backend/src/test/`
- Follow existing test patterns (Testcontainers + Spring Boot test slice)
- Run tests before declaring the feature done: `mvn test` (from the `backend/` directory)
- Tests must pass with no failures before moving on

---

## Database Schema Changes

**Every new or changed column on a JPA entity needs a Flyway migration** in `backend/src/main/resources/db/migration/` (`V<yyyyMMddHHmmss>__description.sql`).

- **Version numbers are timestamps, not sequential integers.** Use the current UTC date/time when creating the file, e.g. `V20260721143000__add_widget_flag.sql` (`date -u +%Y%m%d%H%M%S`). Sequential numbers (`V13`, `V14`, …) collide whenever two branches each add a migration — never create new ones. The pre-existing `V1`–`V12` files keep their numbers; renaming an applied migration breaks Flyway validation.
- `out-of-order: true` is enabled, so a migration merged after a newer-stamped one has been applied still runs.

Do not rely on `ddl-auto: update` to add the column — it does not reliably alter existing tables, and a missing column takes down every read path that touches the entity at runtime.

```sql
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'assessment_workflow_config') THEN
    ALTER TABLE assessment_workflow_config
        ADD COLUMN IF NOT EXISTS allow_self_peer_review BOOLEAN NOT NULL DEFAULT FALSE;
  END IF;
END $$;
```

- **Wrap `ALTER TABLE` on Hibernate-owned tables in a `DO $$ IF EXISTS(table) THEN … END $$` guard.** Flyway runs before Hibernate's schema bootstrap, so on a fresh database the table does not exist yet and an unguarded `ALTER` fails app startup (and any single-test-class run). On fresh installs the entity definition creates the column correctly anyway.
- Use `IF NOT EXISTS` so the migration is idempotent across environments that already drifted
- Give the column a default that matches the entity's `@Builder.Default`, so existing rows stay valid
- Tests will **not** catch a missing migration: `application-test.yml` uses `ddl-auto: create-drop`, which rebuilds the schema from the entities on every run and can never see drift. Green tests do not mean the running database is correct.
