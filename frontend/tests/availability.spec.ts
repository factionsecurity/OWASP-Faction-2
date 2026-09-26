import { test, expect, Page } from '@playwright/test';
import { loginAsSuperAdmin, TEST_CONFIG } from './helpers';

/** Bearer token for direct API calls (config get/set, edition probing) alongside the UI. */
async function authHeaders(page: Page): Promise<{ Authorization: string }> {
  const token = await page.evaluate(() => localStorage.getItem('token'));
  return { Authorization: `Bearer ${token}` };
}

test.describe('Team availability', () => {
  test.beforeEach(async ({ page }) => { await loginAsSuperAdmin(page); });

  test('profile time off can be added and removed', async ({ page }) => {
    await page.goto(`${TEST_CONFIG.baseURL}/account/profile`);
    const card = page.locator('.availability-card');
    // Hidden in the open source edition.
    if (!(await card.isVisible({ timeout: 5000 }).catch(() => false))) { test.skip(true, 'team_scheduling not in this edition'); return; }
    await card.getByRole('button', { name: 'Add time off' }).click();
    const [start, end] = await card.locator('.availability-form input[type="date"]').all();
    await start.fill('2031-03-10');
    await end.fill('2031-03-11');
    await card.getByPlaceholder('Reason (optional)').fill('E2E trip');
    await card.getByRole('button', { name: 'Save' }).click();
    const row = card.locator('li', { hasText: 'E2E trip' });
    await expect(row).toBeVisible();
    await row.getByTitle('Remove').click();
    await page.getByRole('button', { name: 'Remove' }).last().click();
    await expect(row).toHaveCount(0);
  });

  test('admin page shows blocks and holiday calendars, or the locked panel', async ({ page }) => {
    await page.goto(`${TEST_CONFIG.baseURL}/availability`);
    const locked = page.locator('.paid-lock:has-text("Availability")');
    const tabs = page.locator('.availability-tabs');
    await expect(locked.or(tabs)).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
    if (await locked.isVisible()) return;
    await expect(tabs.getByRole('button', { name: 'Blocks' })).toBeVisible();
    await tabs.getByRole('button', { name: 'Holiday calendars' }).click();
    await expect(page.getByText('Organization default')).toBeVisible();
  });

  test('by user timeline: hovering a bar shows the instant hover card', async ({ page }) => {
    await page.goto(`${TEST_CONFIG.baseURL}/scheduling`);
    await expect(page.locator('.engagements-page')).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
    await page.locator('.eng-view-toggle button:has-text("By User")').click();

    const timeline = page.locator('.assessor-timeline');
    const locked = page.locator('.paid-lock:has-text("By User Timeline")');
    await expect(timeline.or(locked)).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
    if (await locked.isVisible()) { test.skip(true, 'team_scheduling not in this edition'); return; }

    // The checkbox that filters rows down to people with an assigned assessment.
    await expect(timeline.getByLabel('Only assigned users')).toBeChecked();

    // Data has finished loading once the grid (rather than the loading spinner) is present.
    await expect(timeline.locator('.tl-grid')).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
    const bar = timeline.locator('.tl-bar').first();
    if ((await timeline.locator('.tl-bar').count()) === 0) {
      test.skip(true, 'no assessments scheduled in the current month');
      return;
    }

    const barName = (await bar.locator('.tl-bar-name').textContent())?.trim();
    await bar.hover();
    const card = page.locator('.tl-hover-card');
    await expect(card).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
    if (barName) await expect(card.locator('.tl-hover-title')).toHaveText(barName);

    // Moving away from the bar hides the card again.
    await page.mouse.move(0, 0);
    await expect(card).toHaveCount(0);
  });
});

/**
 * Both tests below toggle the org-wide default holiday region, a single shared setting — grouped
 * and run serially (rather than left to `fullyParallel`) so two workers never fight over it, and
 * each restores whatever was configured before it ran.
 */
test.describe.serial('Team availability - org default holiday region', () => {
  test.beforeEach(async ({ page }) => { await loginAsSuperAdmin(page); });

  test('admin holiday calendars: add and remove a company day range', async ({ page }) => {
    const headers = await authHeaders(page);
    const configRes = await page.request.get(`${TEST_CONFIG.apiURL}/availability/config`, { headers });
    if (configRes.status() === 402) { test.skip(true, 'team_scheduling not in this edition'); return; }
    const previousDefault: string | null = (await configRes.json()).data?.defaultHolidayRegion ?? null;

    try {
      await page.request.put(`${TEST_CONFIG.apiURL}/availability/config`, { headers, data: { defaultHolidayRegion: 'us' } });

      await page.goto(`${TEST_CONFIG.baseURL}/availability`);
      const tabs = page.locator('.availability-tabs');
      await expect(tabs).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
      await tabs.getByRole('button', { name: 'Holiday calendars' }).click();
      await expect(page.getByText('Organization default')).toBeVisible();

      // The furthest year the "Browse and edit" year picker offers — a far-future range, so it
      // never collides with a real holiday or another test's data.
      const year = new Date().getFullYear() + 2;
      const name = `E2E Range ${Date.now()}`;

      // Region auto-selects to the org default once it loads; the add-company-day form only
      // renders once a region (and its holiday list) is ready.
      const form = page.locator('.availability-form');
      await expect(form).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
      await page.locator('.availability-browse-controls select').selectOption(String(year));
      await expect(form).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });

      const [start, end] = await form.locator('input[type="date"]').all();
      await start.fill(`${year}-06-15`);
      await end.fill(`${year}-06-17`);
      await form.getByPlaceholder('Name').fill(name);
      await form.getByRole('button', { name: 'Add' }).click();

      const row = page.locator('.availability-list li', { hasText: name });
      await expect(row).toHaveCount(1);
      await expect(row).toContainText('Jun 15');
      await expect(row).toContainText('Jun 17');

      await row.getByTitle('Remove').click();
      await expect(row).toHaveCount(0);
    } finally {
      await page.request.put(`${TEST_CONFIG.apiURL}/availability/config`, { headers, data: { defaultHolidayRegion: previousDefault } });
    }
  });

  test('engagements calendar: legend shows Holiday for the org default region', async ({ page }) => {
    const headers = await authHeaders(page);
    const configRes = await page.request.get(`${TEST_CONFIG.apiURL}/availability/config`, { headers });
    if (configRes.status() === 402) { test.skip(true, 'team_scheduling not in this edition'); return; }
    const previousDefault: string | null = (await configRes.json()).data?.defaultHolidayRegion ?? null;

    try {
      await page.request.put(`${TEST_CONFIG.apiURL}/availability/config`, { headers, data: { defaultHolidayRegion: 'us' } });

      await page.goto(`${TEST_CONFIG.baseURL}/scheduling`);
      await expect(page.locator('.engagements-page')).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
      const calendarToggle = page.locator('button:has-text("Calendar View")');
      if (await calendarToggle.isVisible()) await calendarToggle.click();
      await expect(page.locator('.assessment-calendar')).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });

      // Navigate to November 2026 — the US calendar has Thanksgiving that month.
      const target = new Date(2026, 10, 1);
      const now = new Date();
      const diff = (target.getFullYear() - now.getFullYear()) * 12 + (target.getMonth() - now.getMonth());
      const navBtn = diff >= 0 ? page.locator('.fc-next-button') : page.locator('.fc-prev-button');
      for (let i = 0; i < Math.abs(diff); i++) {
        await navBtn.click();
      }
      await expect(page.locator('.fc-toolbar-title')).toContainText('November 2026', { timeout: TEST_CONFIG.timeout.medium });

      await expect(page.locator('.calendar-legend')).toContainText('Holiday', { timeout: TEST_CONFIG.timeout.medium });
    } finally {
      await page.request.put(`${TEST_CONFIG.apiURL}/availability/config`, { headers, data: { defaultHolidayRegion: previousDefault } });
    }
  });
});
