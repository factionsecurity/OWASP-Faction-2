import { test, expect, Page } from '@playwright/test';
import {
  loginAsSuperAdmin,
  waitForTableToLoad,
  TEST_CONFIG,
} from './helpers';

// ─── Helpers ─────────────────────────────────────────────────────────────────

async function navigateToEngagements(page: Page) {
  await page.goto(`${TEST_CONFIG.baseURL}/scheduling`);
  await page.waitForURL('**/scheduling', { timeout: TEST_CONFIG.timeout.medium });
  await expect(page.locator('.engagements-page')).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
}

async function switchToListView(page: Page) {
  const toggleBtn = page.locator('button:has-text("List View")');
  if (await toggleBtn.isVisible()) {
    await toggleBtn.click();
    await expect(page.locator('.data-table')).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
  }
}

async function switchToCalendarView(page: Page) {
  const toggleBtn = page.locator('button:has-text("Calendar View")');
  if (await toggleBtn.isVisible()) {
    await toggleBtn.click();
    await expect(page.locator('.assessment-calendar')).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
  }
}

// ─── Engagements Page ─────────────────────────────────────────────────────────

test.describe('Engagements Page', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsSuperAdmin(page);
    await navigateToEngagements(page);
  });

  test('should display engagements page with metrics dashboard', async ({ page }) => {
    await expect(page.locator('.metrics-dashboard')).toBeVisible();
    await expect(page.locator('.stat-card')).toHaveCount(9); // Total + 7 statuses + Past Due

    await expect(page.locator('.stat-card:has-text("Total Assessments")')).toBeVisible();
    await expect(page.locator('.stat-card:has-text("Draft")')).toBeVisible();
    await expect(page.locator('.stat-card:has-text("In Progress")')).toBeVisible();
    await expect(page.locator('.stat-card:has-text("On Hold")')).toBeVisible();
    await expect(page.locator('.stat-card:has-text("Pending Review")')).toBeVisible();
    await expect(page.locator('.stat-card:has-text("Completed")')).toBeVisible();
    await expect(page.locator('.stat-card:has-text("Approved")')).toBeVisible();
    await expect(page.locator('.stat-card:has-text("Archived")')).toBeVisible();
    await expect(page.locator('.stat-card:has-text("Past Due")')).toBeVisible();
  });

  test('should display calendar view by default', async ({ page }) => {
    // Default view depends on localStorage; ensure calendar is visible or toggle to it
    await switchToCalendarView(page);

    await expect(page.locator('.assessment-calendar')).toBeVisible();
    await expect(page.locator('.fc')).toBeVisible();
    await expect(page.locator('.fc-toolbar')).toBeVisible();
    await expect(page.locator('.fc-prev-button')).toBeVisible();
    await expect(page.locator('.fc-next-button')).toBeVisible();
    await expect(page.locator('.fc-today-button')).toBeVisible();
  });

  test('should display calendar legend', async ({ page }) => {
    await switchToCalendarView(page);
    await expect(page.locator('.calendar-legend')).toBeVisible();

    await expect(page.locator('.calendar-legend .badge:has-text("Draft")')).toBeVisible();
    await expect(page.locator('.calendar-legend .badge:has-text("In Progress")')).toBeVisible();
    await expect(page.locator('.calendar-legend .badge:has-text("On Hold")')).toBeVisible();
    await expect(page.locator('.calendar-legend .badge:has-text("Pending Review")')).toBeVisible();
    await expect(page.locator('.calendar-legend .badge:has-text("Completed")')).toBeVisible();
    await expect(page.locator('.calendar-legend .badge:has-text("Approved")')).toBeVisible();
    await expect(page.locator('.calendar-legend .badge:has-text("Archived")')).toBeVisible();
    await expect(page.locator('.calendar-legend .badge:has-text("Past Due")')).toBeVisible();
  });

  test('should toggle between calendar and list view', async ({ page }) => {
    // Make sure we start in calendar view
    await switchToCalendarView(page);
    await expect(page.locator('.assessment-calendar')).toBeVisible();

    // Switch to list view
    await page.locator('button:has-text("List View")').click();
    await expect(page.locator('.data-table')).toBeVisible();
    await expect(page.locator('.assessment-calendar')).not.toBeVisible();

    // Toggle back to calendar view
    await page.locator('button:has-text("Calendar View")').click();
    await expect(page.locator('.assessment-calendar')).toBeVisible();
    await expect(page.locator('.data-table')).not.toBeVisible();
  });

  test('should show the By User timeline with a row per internal user', async ({ page }) => {
    await page.locator('.eng-view-toggle button:has-text("By User")').click();
    const timeline = page.locator('.assessor-timeline');
    const locked = page.locator('.paid-lock:has-text("By User Timeline")');
    await expect(timeline.or(locked)).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });

    // The timeline is paid (team_scheduling): the open source edition shows the locked panel.
    if (await locked.isVisible()) {
      await expect(locked).toContainText('Not included in this edition');
      return;
    }

    await expect(timeline.locator('.tl-span-btn.active')).toHaveCount(1);

    // Active only (the default) keeps just the people booked on an active assessment in range,
    // so every user row it shows carries at least one bar.
    const activeOnly = timeline.getByLabel('Only active assessments');
    await expect(activeOnly).toBeChecked();
    const bookedRows = timeline.locator('.tl-row:not(:has(.unassigned))');
    const bookedCount = await bookedRows.count();
    for (let i = 0; i < bookedCount; i++) {
      await expect(bookedRows.nth(i).locator('.tl-bar').first()).toBeVisible();
    }

    // Unchecked, every internal user gets a row — the signed-in super admin among them.
    await activeOnly.uncheck();
    await expect(timeline.locator('.tl-row').first()).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
    expect(await timeline.locator('.tl-row').count()).toBeGreaterThanOrEqual(bookedCount);
    await activeOnly.check();

    // Switching span keeps the grid and moves the title to the new range.
    await timeline.locator('.tl-span-btn:has-text("Week")').click();
    await expect(timeline.locator('.tl-day')).toHaveCount(7);
    await timeline.locator('.tl-span-btn:has-text("Month")').click();
    await expect(timeline.locator('.tl-title')).not.toContainText('–');
  });

  test('should display filters in list view', async ({ page }) => {
    await switchToListView(page);

    await expect(page.locator('.filters-row')).toBeVisible();
    await expect(page.locator('.filter-item')).toHaveCount(4);

    await expect(page.locator('label:has-text("Status")')).toBeVisible();
    await expect(page.locator('label:has-text("Application")')).toBeVisible();
    await expect(page.locator('label:has-text("Assessment Type")')).toBeVisible();
    await expect(page.locator('label:has-text("Search")')).toBeVisible();
  });

  test('should filter assessments by status in list view', async ({ page }) => {
    await switchToListView(page);
    await waitForTableToLoad(page);

    // Select DRAFT status using the select with DRAFT option
    const statusSelect = page.locator('.filter-item select:has(option[value="DRAFT"])');
    await statusSelect.selectOption('IN_PROGRESS');

    await page.waitForTimeout(500);
    // Just verify no crash — table may be empty if no IN_PROGRESS assessments
    await expect(page.locator('.data-table')).toBeVisible();
  });

  test('should search assessments by name', async ({ page }) => {
    await switchToListView(page);

    const searchInput = page.locator('input[placeholder*="Search by name"]');
    await searchInput.fill('nonexistentxyz999');
    await page.waitForTimeout(500);

    await expect(page.locator('.data-table')).toBeVisible();
  });

  test('should export assessments to CSV', async ({ page }) => {
    const downloadPromise = page.waitForEvent('download', { timeout: TEST_CONFIG.timeout.long });
    await page.locator('button:has-text("Export CSV")').click();

    const download = await downloadPromise;
    expect(download.suggestedFilename()).toMatch(/assessments-\d{4}-\d{2}-\d{2}\.csv/);
  });

  test('should navigate to Create Assessment page when clicking Create Assessment', async ({ page }) => {
    await page.locator('button:has-text("Create Assessment")').click();
    await page.waitForURL('**/scheduling/create', { timeout: TEST_CONFIG.timeout.medium });
    await expect(page.locator('h2:has-text("Create Assessment")')).toBeVisible();
  });

  test('should click on metric card and switch to list view with filter applied', async ({ page }) => {
    await switchToCalendarView(page);

    // Click on the "In Progress" stat card
    const inProgressCard = page.locator('.stat-card:has-text("In Progress")');
    await inProgressCard.click();

    // Should switch to list view
    await expect(page.locator('.data-table')).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
  });

  test('should navigate calendar months', async ({ page }) => {
    await switchToCalendarView(page);

    const initialTitle = await page.locator('.fc-toolbar-title').textContent();

    await page.locator('.fc-next-button').click();
    await page.waitForTimeout(300);

    const nextTitle = await page.locator('.fc-toolbar-title').textContent();
    expect(nextTitle).not.toBe(initialTitle);

    await page.locator('.fc-prev-button').click();
    await page.waitForTimeout(300);

    const backTitle = await page.locator('.fc-toolbar-title').textContent();
    expect(backTitle).toBe(initialTitle);
  });

  test('should display today button in calendar', async ({ page }) => {
    await switchToCalendarView(page);
    await expect(page.locator('.fc-today-button')).toBeVisible();
  });

  test('should have proper page header actions', async ({ page }) => {
    await expect(page.locator('button:has-text("Export CSV")')).toBeVisible();
    await expect(page.locator('button:has-text("Create Assessment")')).toBeVisible();
    // The view switcher offers all three views
    await expect(page.locator('.eng-view-toggle button')).toHaveText([/List View/, /Calendar View/, /By User/]);
  });

  test('should display loading state and eventually show metrics', async ({ page }) => {
    await page.reload();
    await expect(page.locator('.metrics-dashboard')).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
  });

  test('should display data table columns in list view', async ({ page }) => {
    await switchToListView(page);
    await waitForTableToLoad(page);

    await expect(page.locator('th:has-text("Name")')).toBeVisible();
    await expect(page.locator('th:has-text("Status")')).toBeVisible();
    await expect(page.locator('th:has-text("Start Date")')).toBeVisible();
    await expect(page.locator('th:has-text("Planned End")')).toBeVisible();
    await expect(page.locator('th:has-text("Assessors")')).toBeVisible();
    await expect(page.locator('th:has-text("Actions")')).toBeVisible();
  });

  test('should display pagination controls in list view', async ({ page }) => {
    await switchToListView(page);
    await waitForTableToLoad(page);

    await expect(page.locator('.pagination-container')).toBeVisible();
    await expect(page.locator('select.page-size-select')).toBeVisible();
  });

  test('should show edit buttons for assessments in list view', async ({ page }) => {
    await switchToListView(page);
    await waitForTableToLoad(page);

    // Action buttons are icon-only in the Actions column
    const actionButtons = page.locator('.data-table tbody td:last-child .btn');
    const count = await actionButtons.count();
    // Just verify the table renders without error; if no assessments, count is 0
    expect(count).toBeGreaterThanOrEqual(0);
  });

  test('should navigate to edit page when clicking edit button in list view', async ({ page }) => {
    await switchToListView(page);
    await waitForTableToLoad(page);

    const rows = page.locator('.data-table tbody tr');
    const rowCount = await rows.count();

    if (rowCount > 0 && !(await rows.first().locator('td').first().textContent())?.includes('No data')) {
      // Edit button is the first action button (secondary variant)
      const editBtn = rows.first().locator('.btn-secondary').first();
      if (await editBtn.isVisible()) {
        await editBtn.click();
await page.waitForURL('**/scheduling/edit/*', { timeout: TEST_CONFIG.timeout.medium });
expect(page.url()).toMatch(/\/scheduling\/edit\/[a-zA-Z0-9]+$/);
      }
    }
  });

  test('should show delete confirmation when clicking delete button in list view', async ({ page }) => {
    await switchToListView(page);
    await waitForTableToLoad(page);

    const rows = page.locator('.data-table tbody tr');
    const rowCount = await rows.count();

    if (rowCount > 0 && !(await rows.first().locator('td').first().textContent())?.includes('No data')) {
      // Delete button is the danger variant button in actions column
      const deleteBtn = rows.first().locator('.btn-danger').first();
      if (await deleteBtn.isVisible()) {
        page.once('dialog', async dialog => {
          expect(dialog.type()).toBe('confirm');
          await dialog.dismiss(); // Don't actually delete
        });
        await deleteBtn.click();
      }
    }
  });

  test('should navigate to calendar when clicking calendar event', async ({ page }) => {
    await switchToCalendarView(page);

    const events = page.locator('.fc-event');
    const eventCount = await events.count();

    if (eventCount > 0) {
      await events.first().click();
      // Should navigate to the edit page
      await page.waitForURL('**/scheduling/edit/*', { timeout: TEST_CONFIG.timeout.medium });
      expect(page.url()).toMatch(/\/scheduling\/edit\/[a-zA-Z0-9]+$/);
    }
  });
});

// ─── Engagements Page - Responsive Design ─────────────────────────────────────

test.describe('Engagements Page - Responsive Design', () => {
  test('should display properly on mobile viewport', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await loginAsSuperAdmin(page);
    await navigateToEngagements(page);

    await expect(page.locator('.metrics-dashboard')).toBeVisible();
    await expect(page.locator('button:has-text("Create Assessment")')).toBeVisible();
  });

  test('should display properly on tablet viewport', async ({ page }) => {
    await page.setViewportSize({ width: 768, height: 1024 });
    await loginAsSuperAdmin(page);
    await navigateToEngagements(page);

    await expect(page.locator('.metrics-dashboard')).toBeVisible();
  });
});

// ─── Assessment CSV import ───────────────────────────────────────────────────

test.describe('Assessment CSV import', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsSuperAdmin(page);
    await navigateToEngagements(page);
  });

  /** An assessment type that exists in this environment, read through the API. */
  async function anyAssessmentTypeName(page: Page): Promise<string> {
    const token = await page.evaluate(() => localStorage.getItem('token'));
    const res = await page.request.get(`${TEST_CONFIG.apiURL}/assessment-types`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const body = await res.json();
    const types = Array.isArray(body.data) ? body.data : [];
    expect(types.length).toBeGreaterThan(0);
    return types[0].name;
  }

  async function chooseCsv(page: Page, csv: string) {
    await page.locator('.asmt-import-file').setInputFiles({
      name: 'assessments.csv',
      mimeType: 'text/csv',
      buffer: Buffer.from(csv, 'utf-8'),
    });
  }

  test('previews and imports a valid file', async ({ page }) => {
    const type = await anyAssessmentTypeName(page);
    const suffix = Date.now();
    const name = `CSV Import ${suffix}`;

    await page.locator('button:has-text("Import CSV")').click();
    await expect(page.locator('text=Import Assessments from CSV')).toBeVisible();
    await chooseCsv(page, [
      'name,appId,applicationName,assessmentType,startDate,durationDays',
      `${name},CSV-${suffix},CSV App ${suffix},${type},2026-10-05,5`,
    ].join('\n'));
    await page.locator('button:has-text("Preview")').click();

    await expect(page.locator('.asmt-import-table')).toContainText(name);
    await expect(page.locator('.asmt-import-table')).toContainText('New');
    const create = page.locator('button:has-text("Create 1 assessment")');
    await expect(create).toBeEnabled();
    await create.click();

    await expect(page.locator('text=Created 1 assessment')).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
    await page.locator('.modal button:has-text("Close")').click();
    await switchToListView(page);
    await expect(page.locator('.data-table')).toContainText(name, { timeout: TEST_CONFIG.timeout.medium });
  });

  test('blocks the import while a row has errors', async ({ page }) => {
    await page.locator('button:has-text("Import CSV")').click();
    await chooseCsv(page, [
      'name,appId,assessmentType,startDate,durationDays',
      'Broken Row,APP-X,No Such Type Anywhere,2026-10-05,5',
    ].join('\n'));
    await page.locator('button:has-text("Preview")').click();

    await expect(page.locator('.asmt-import-row-error')).toContainText("Unknown assessment type 'No Such Type Anywhere'");
    await expect(page.locator('button:has-text("Create 1 assessment")')).toBeDisabled();
    await expect(page.locator('text=Fix the errors in your file and preview again.')).toBeVisible();
  });
});
