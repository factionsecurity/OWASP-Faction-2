# Verify frontend changes (component-level, no backend)

The full Playwright suite (`npm test`) needs the backend + auth on localhost:3000.
For component-level changes (e.g. `RichTextEditor`), mount the real component in a
throwaway Vite harness instead — no backend needed.

## Recipe

1. Create `frontend/rte-harness.html`:
   ```html
   <!DOCTYPE html>
   <html><head><title>harness</title></head>
   <body><div id="root"></div><script type="module" src="/src/harness-main.tsx"></script></body></html>
   ```
2. Create `frontend/src/harness-main.tsx` that renders the component under test with
   `createRoot`, wiring state so `onChange` output is visible in a `<pre id="html-out">`.
3. Start dev server: `npx vite --port 5199 --strictPort` (background). Harness serves at
   `http://localhost:5199/rte-harness.html` — Vite treats any root-level .html as an entry.
4. Drive with Playwright (installed in `frontend/node_modules`):
   `import { chromium } from '<repo>/frontend/node_modules/playwright/index.mjs'`
   Use real `page.keyboard.type/press` against the component's DOM
   (RichTextEditor's contenteditable root is `.rte-body`).
5. Assert on `editor.innerHTML()` and `#html-out` text; screenshot for evidence.
6. Clean up: kill Vite, delete the two harness files.

## Gotchas

- RichTextEditor coalesces typing into onChange/history for 500ms — `waitForTimeout(600)`
  before reading `#html-out`, and before pressing Ctrl+Z after typing.
- Importing components that pull in `../api` is fine without a backend; requests only
  fire on interaction (e.g. `@mention` lookups).
- Pre-existing typecheck error: unused `ClipboardList` import in DashboardLayout.tsx.
- To create a code block in tests, click `button[title="Code block"]` — the ``` typed
  shortcut misbehaves on a bare text node in an empty editor (formatBlock no-ops or
  swallows the previous bare-text line).
- To assert caret position, insert a temporary marker span at the selection and measure
  its rect — a collapsed Range's own getBoundingClientRect is often 0×0.
