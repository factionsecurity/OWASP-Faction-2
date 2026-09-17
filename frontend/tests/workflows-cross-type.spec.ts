import { test, expect, Page } from '@playwright/test';
import { loginAsSuperAdmin, waitForTableToLoad, TEST_CONFIG } from './helpers';

/**
 * Cross-type screens resolving each row's own workflow (phase 5b), against a running app.
 *
 * Phases 1-5a gave every assessment its own `workflowId`; phase 5b moved the nine cross-type
 * screens (assessments list, calendar, engagements, dashboards, vulnerabilities, remediation,
 * application pages…) off the single legacy global config so each row's status name, colour
 * and label resolve against its OWN workflow instead of one shared one.
 *
 * The rule this spec exists to prove (see `src/utils/workflowLookup.ts`): a status name renders
 * bare — "In Review" — UNLESS that same name appears in more than one workflow with a DIFFERENT
 * colour, in which case every occurrence carries its workflow — "In Review (PCI Workflow)". Same
 * name + same colour across workflows is not a collision and stays bare.
 *
 * Fixture (built entirely through the API in `beforeEach`, deleted in `afterEach`):
 *  - a second workflow ("PCI Workflow" below) cloned from Default Workflow, then edited so one
 *    of its statuses keeps Default Workflow's name but gets a DIFFERENT colour (the collision),
 *    and a brand-new status name is added that exists nowhere else (never ambiguous — always
 *    bare, regardless of colour);
 *  - two assessment types, one on each workflow, so an assessment can be created "as" either;
 *  - one application and three assessments:
 *      - "Collide (Default)"  — Default Workflow, colliding status name
 *      - "Collide (PCI)"      — PCI Workflow,     colliding status name (different colour)
 *      - "Solo (PCI)"         — PCI Workflow,     the unique, never-ambiguous status name
 *
 * Do NOT run this file as part of an automated task — it needs the app and API live at
 * TEST_CONFIG.baseURL / TEST_CONFIG.apiURL. It is left for Josh to run against a running stack.
 *
 * Caveat inherited from every other spec in this suite: it asserts against a shared dev
 * database. If some other pre-existing workflow happens to reuse the same collision status name
 * with yet another distinct colour, the calendar legend will show a third swatch for that name —
 * that does not invalidate this spec's own two swatches, but a strict count assertion on the
 * legend would be flaky, so this spec checks for the two colours it created rather than an exact
 * total.
 */

// ─── Fixture helpers ──────────────────────────────────────────────────────────

async function authHeaders(page: Page): Promise<Record<string, string>> {
  const token = await page.evaluate(() => localStorage.getItem('token'));
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/** "#rrggbb" to the "rgb(r, g, b)" string the DOM reports back from getComputedStyle. */
function hexToRgb(hex: string): string {
  const clean = hex.replace('#', '');
  const r = parseInt(clean.substring(0, 2), 16);
  const g = parseInt(clean.substring(2, 4), 16);
  const b = parseInt(clean.substring(4, 6), 16);
  return `rgb(${r}, ${g}, ${b})`;
}

/** A near-future, zone-less LocalDateTime string, the shape the API expects for date-only fields. */
function apiDate(daysFromNow: number): string {
  const dt = new Date();
  dt.setDate(dt.getDate() + daysFromNow);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())}T00:00:00`;
}

async function navigateToEngagements(page: Page) {
  await page.goto('/scheduling');
  await page.waitForURL('**/scheduling', { timeout: TEST_CONFIG.timeout.medium });
  await expect(page.locator('.engagements-page')).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
}

async function switchToCalendarView(page: Page) {
  const toggleBtn = page.locator('button:has-text("Calendar View")');
  if (await toggleBtn.isVisible().catch(() => false)) {
    await toggleBtn.click();
  }
  await expect(page.locator('.assessment-calendar')).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
}

// ─── Spec ──────────────────────────────────────────────────────────────────────

test.describe('Cross-type workflow rendering', () => {
  // Computed per test (not once at describe scope) so two fixtures never collide on name
  // uniqueness when Playwright runs tests in parallel across projects (see file header caveat
  // about the shared dev database — a name collision would fail a test for a reason that has
  // nothing to do with the code under test). This keeps the tests independent rather than
  // serialising them with `test.describe.configure({ mode: 'serial' })`.
  let ts = 0;
  let workflowName = '';
  let soloStatus = '';
  const soloColor = '#22c55e';
  let namePrefix = '';

  let defaultWorkflowName = 'Default Workflow';
  let collidingStatus = '';
  let defaultColor = '';
  let pciColor = '';

  let workflowId: string | null = null;
  let pciTypeId: string | null = null;
  let defaultTypeId: string | null = null;
  let applicationId: string | null = null;
  let collideDefaultAssessmentId: string | null = null;
  let collidePciAssessmentId: string | null = null;
  let soloAssessmentId: string | null = null;

  test.beforeEach(async ({ page }) => {
    ts = Date.now();
    workflowName = `E2E PCI Workflow ${ts}`;
    soloStatus = `E2E Solo Status ${ts}`;
    namePrefix = `E2E XT ${ts}`;

    await loginAsSuperAdmin(page);
    const headers = await authHeaders(page);

    // Default Workflow, read live so the collision is built against whatever it actually has.
    const defaultRes = await page.request.get(`${TEST_CONFIG.apiURL}/workflows/default`, { headers });
    expect(defaultRes.ok()).toBeTruthy();
    const defaultWorkflow = (await defaultRes.json()).data;
    defaultWorkflowName = defaultWorkflow.name;

    // A status that already carries an explicit colour in Default Workflow, and isn't its
    // completed status (the assessments list hides completed assessments by default, which
    // would make the fixture assessment disappear from the very screen under test).
    const coloredEntries = Object.entries(defaultWorkflow.statusColors ?? {}) as [string, string][];
    const collisionCandidate = coloredEntries.find(([name]) => name !== defaultWorkflow.completedStatus);
    test.skip(!collisionCandidate, 'Default Workflow has no non-terminal coloured status to collide on');
    [collidingStatus, defaultColor] = collisionCandidate!;
    // A colour guaranteed different from Default Workflow's for the same name — the collision.
    pciColor = defaultColor.toLowerCase() === '#ef4444' ? '#0ea5e9' : '#ef4444';

    // Clone Default Workflow, then edit the clone: recolour the colliding status and add the
    // solo (never-ambiguous) one.
    const createRes = await page.request.post(`${TEST_CONFIG.apiURL}/workflows`, {
      headers,
      data: { sourceWorkflowId: defaultWorkflow.id, name: workflowName },
    });
    // POST /workflows is gated by Feature.CUSTOM_WORKFLOWS and answers 402 on the community
    // edition — skip rather than fail there, same as workflows.spec.ts:39-40. A genuine failure
    // (500, 400, auth) still fails loudly.
    test.skip(createRes.status() === 402, 'Custom Workflows is not in this edition');
    expect(createRes.ok()).toBeTruthy();
    const created = (await createRes.json()).data;
    workflowId = created.id;

    const statuses = [
      ...created.statuses.map((name: string) => ({ originalName: name, name })),
      { originalName: null, name: soloStatus },
    ];
    const statusColors = {
      ...(created.statusColors ?? {}),
      [collidingStatus]: pciColor,
      [soloStatus]: soloColor,
    };
    const vulnerabilityStatuses = (created.vulnerabilityStatuses ?? []).map((name: string) => ({
      originalName: name,
      name,
    }));

    const updateRes = await page.request.put(`${TEST_CONFIG.apiURL}/workflows/${workflowId}`, {
      headers,
      data: {
        name: workflowName,
        statuses,
        newAssessmentStatus: created.newAssessmentStatus,
        inProgressStatus: created.inProgressStatus,
        completedStatus: created.completedStatus,
        statusColors,
        vulnerabilitySlas: created.vulnerabilitySlas ?? [],
        vulnerabilityStatuses,
        remediationStages: created.remediationStages,
        allowSelfPeerReview: created.allowSelfPeerReview,
      },
    });
    expect(updateRes.ok()).toBeTruthy();

    // Two assessment types — one per workflow — and one application to hang assessments off.
    const pciTypeRes = await page.request.post(`${TEST_CONFIG.apiURL}/assessment-types`, {
      headers,
      data: { name: `E2E PCI Type ${ts}`, description: 'workflows-cross-type spec', active: true, workflowId },
    });
    // Assigning a non-default workflowId hits the same CUSTOM_WORKFLOWS gate (AssessmentTypeService
    // .resolveWorkflowId). Unreachable today under community, since the workflow create above
    // already skips first — kept as a guard in case that ordering ever changes.
    test.skip(pciTypeRes.status() === 402, 'Custom Workflows is not in this edition');
    expect(pciTypeRes.ok()).toBeTruthy();
    pciTypeId = (await pciTypeRes.json()).data.id;

    const defaultTypeRes = await page.request.post(`${TEST_CONFIG.apiURL}/assessment-types`, {
      headers,
      // workflowId omitted: Default Workflow.
      data: { name: `E2E Default Type ${ts}`, description: 'workflows-cross-type spec', active: true },
    });
    expect(defaultTypeRes.ok()).toBeTruthy();
    defaultTypeId = (await defaultTypeRes.json()).data.id;

    const appRes = await page.request.post(`${TEST_CONFIG.apiURL}/applications`, {
      headers,
      data: { name: `${namePrefix} App` },
    });
    expect(appRes.ok()).toBeTruthy();
    applicationId = (await appRes.json()).data.id;

    // Three assessments, dated a few days out so the calendar's (month-1 .. month+2) window
    // always includes them regardless of when this runs.
    const createAssessment = async (name: string, assessmentTypeId: string) => {
      const res = await page.request.post(`${TEST_CONFIG.apiURL}/assessments`, {
        headers,
        data: {
          name,
          applicationId,
          assessmentTypeId,
          startDate: apiDate(2),
          plannedEndDate: apiDate(9),
        },
      });
      expect(res.ok()).toBeTruthy();
      return (await res.json()).data.id as string;
    };

    const setStatus = async (assessmentId: string, status: string) => {
      const res = await page.request.put(`${TEST_CONFIG.apiURL}/assessments/${assessmentId}`, {
        headers,
        data: { status },
      });
      expect(res.ok()).toBeTruthy();
    };

    collideDefaultAssessmentId = await createAssessment(`${namePrefix} Collide (Default)`, defaultTypeId!);
    await setStatus(collideDefaultAssessmentId, collidingStatus);

    collidePciAssessmentId = await createAssessment(`${namePrefix} Collide (PCI)`, pciTypeId!);
    await setStatus(collidePciAssessmentId, collidingStatus);

    soloAssessmentId = await createAssessment(`${namePrefix} Solo (PCI)`, pciTypeId!);
    await setStatus(soloAssessmentId, soloStatus);
  });

  // A failed run must not leave any of this fixture in the shared database. Deletion order
  // matters: an assessment type or workflow still in use refuses to delete.
  //
  // `APIRequestContext.delete` does NOT throw on 4xx/5xx, so a bare `.catch(() => undefined)`
  // (the previous approach) silently swallows a failed delete with no signal at all. That's worse
  // than usual here: these specs run against a shared dev database, and a leftover second
  // workflow carrying a colliding status permanently changes status labels/colours on every
  // screen, for every later run of any spec. So every delete's response is checked; a leak is
  // logged with what leaked and its id, and the workflow delete specifically fails the test (it's
  // the one leak that poisons the shared environment). One failed delete never stops the rest —
  // every remaining fixture is still attempted.
  test.afterEach(async ({ page }) => {
    const headers = await authHeaders(page);
    const leaks: string[] = [];

    const del = async (url: string, label: string): Promise<boolean> => {
      try {
        const res = await page.request.delete(url, { headers });
        if (!res.ok()) {
          leaks.push(`${label} — HTTP ${res.status()} deleting ${url}`);
          return false;
        }
        return true;
      } catch (err) {
        leaks.push(`${label} — delete request threw: ${err}`);
        return false;
      }
    };

    for (const id of [collideDefaultAssessmentId, collidePciAssessmentId, soloAssessmentId]) {
      if (id) await del(`${TEST_CONFIG.apiURL}/assessments/${id}`, `assessment ${id}`);
    }
    collideDefaultAssessmentId = collidePciAssessmentId = soloAssessmentId = null;

    for (const id of [pciTypeId, defaultTypeId]) {
      if (id) await del(`${TEST_CONFIG.apiURL}/assessment-types/${id}`, `assessment type ${id}`);
    }
    pciTypeId = defaultTypeId = null;

    if (applicationId) await del(`${TEST_CONFIG.apiURL}/applications/${applicationId}`, `application ${applicationId}`);
    applicationId = null;

    let workflowDeleted = true;
    const leakedWorkflowId = workflowId;
    if (workflowId) {
      workflowDeleted = await del(`${TEST_CONFIG.apiURL}/workflows/${workflowId}`, `workflow ${workflowId} (${workflowName})`);
    }
    workflowId = null;

    if (leaks.length > 0) {
      // eslint-disable-next-line no-console
      console.warn(
        `[workflows-cross-type] cleanup left fixture data behind in the shared dev database:\n  ${leaks.join('\n  ')}`,
      );
    }

    // The workflow is the one leak that actually poisons the shared environment (its colliding
    // status carries into every later run's screens), so it must fail loudly rather than just log.
    expect(
      workflowDeleted,
      `Workflow "${workflowName}" (${leakedWorkflowId}) failed to delete and is now leaked in the shared ` +
        `dev database — it will affect status labels/colours for every later spec run until removed by hand.`,
    ).toBeTruthy();
  });

  test('assessments list colours each row from its own workflow, tags the colliding name, leaves the solo one bare', async ({ page }) => {
    await page.goto('/assessments');
    await page.waitForURL('**/assessments', { timeout: TEST_CONFIG.timeout.medium });
    await waitForTableToLoad(page);

    await page.locator('.dt-search-input').fill(namePrefix);
    await expect(page.locator('tr', { hasText: `${namePrefix} Solo (PCI)` })).toBeVisible({
      timeout: TEST_CONFIG.timeout.medium,
    });

    // Default Workflow's row: the colliding status, tagged with Default Workflow's own name,
    // in Default Workflow's colour.
    const defaultRow = page.locator('tr', { hasText: `${namePrefix} Collide (Default)` });
    const defaultLabel = `${collidingStatus} (${defaultWorkflowName})`;
    const defaultBadge = defaultRow.locator('.badge', { hasText: defaultLabel });
    await expect(defaultBadge).toHaveText(defaultLabel);
    await expect(defaultBadge).toHaveCSS('color', hexToRgb(defaultColor));

    // PCI Workflow's row: the SAME status name, tagged with PCI Workflow's name, in PCI
    // Workflow's (different) colour.
    const pciRow = page.locator('tr', { hasText: `${namePrefix} Collide (PCI)` });
    const pciLabel = `${collidingStatus} (${workflowName})`;
    const pciBadge = pciRow.locator('.badge', { hasText: pciLabel });
    await expect(pciBadge).toHaveText(pciLabel);
    await expect(pciBadge).toHaveCSS('color', hexToRgb(pciColor));

    // The unique status name never collides, so it renders bare — no "(PCI Workflow)" suffix —
    // even though it belongs to the same (non-default) workflow as the row above.
    const soloRow = page.locator('tr', { hasText: `${namePrefix} Solo (PCI)` });
    const soloBadge = soloRow.locator('.badge', { hasText: soloStatus });
    await expect(soloBadge).toHaveText(soloStatus);
    await expect(soloBadge).toHaveCSS('color', hexToRgb(soloColor));
  });

  test('calendar legend lists both colours for the colliding status name', async ({ page }) => {
    await navigateToEngagements(page);
    await switchToCalendarView(page);

    const legend = page.locator('.calendar-legend');
    await expect(legend).toBeVisible();

    const defaultLabel = `${collidingStatus} (${defaultWorkflowName})`;
    const pciLabel = `${collidingStatus} (${workflowName})`;

    const defaultEntry = legend.locator('.badge', { hasText: defaultLabel });
    await expect(defaultEntry).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
    await expect(defaultEntry).toHaveCSS('background-color', hexToRgb(defaultColor));

    const pciEntry = legend.locator('.badge', { hasText: pciLabel });
    await expect(pciEntry).toBeVisible();
    await expect(pciEntry).toHaveCSS('background-color', hexToRgb(pciColor));

    // The solo status still renders bare on the calendar too.
    const soloEntry = legend.locator('.badge', { hasText: soloStatus }).filter({ hasNotText: '(' });
    await expect(soloEntry).toBeVisible();
    await expect(soloEntry).toHaveCSS('background-color', hexToRgb(soloColor));
  });
});
