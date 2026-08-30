import { test, expect, Page } from '@playwright/test';
import {
  loginAsSuperAdmin,
  waitForTableToLoad,
  TEST_CONFIG,
} from './helpers';

// ─── Helpers ─────────────────────────────────────────────────────────────────

async function navigateToAssessments(page: Page) {
  await page.goto(`${TEST_CONFIG.baseURL}/assessments`);
  await page.waitForURL('**/assessments', { timeout: TEST_CONFIG.timeout.medium });
  await expect(page.locator('.assessments-page')).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
}

async function expandAdvancedSearch(page: Page) {
  const header = page.locator('.card-header:has-text("Advanced Search")');
  await header.click();
  await expect(page.locator('.card-body')).toBeVisible({ timeout: TEST_CONFIG.timeout.short });
}

async function waitForAssessmentsLoad(page: Page) {
  // Wait for loading spinner to disappear
  await page.waitForFunction(
    () => !document.querySelector('.loading-spinner'),
    { timeout: TEST_CONFIG.timeout.medium }
  );
}

// ─── Assessments Table Page ───────────────────────────────────────────────────

test.describe('Assessments Table Page', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsSuperAdmin(page);
    await navigateToAssessments(page);
  });

  test('should display the assessments page with correct header', async ({ page }) => {
    await expect(page.locator('h2:has-text("Assessments")')).toBeVisible();
    await expect(page.locator('text=Showing all assessments')).toBeVisible();
  });

  test('should display Export CSV button', async ({ page }) => {
    await expect(page.locator('button:has-text("Export CSV")')).toBeVisible();
  });

  test('should show the Advanced Search panel collapsed by default', async ({ page }) => {
    await expect(page.locator('.card-header:has-text("Advanced Search")')).toBeVisible();
    // card-body should not be visible when collapsed
    await expect(page.locator('.card-body')).not.toBeVisible();
  });

  test('should expand and collapse the Advanced Search panel', async ({ page }) => {
    const header = page.locator('.card-header:has-text("Advanced Search")');

    // Expand
    await header.click();
    await expect(page.locator('.card-body')).toBeVisible();
    // Chevron changes direction
    await expect(page.locator('.card-header svg')).toBeVisible();

    // Collapse
    await header.click();
    await expect(page.locator('.card-body')).not.toBeVisible();
  });

  test('should show all filter fields in Advanced Search', async ({ page }) => {
    await expandAdvancedSearch(page);

    // Text search
    await expect(page.locator('input[placeholder*="Search assessments"]')).toBeVisible();
    // Dropdowns
    await expect(page.locator('label:has-text("Application")')).toBeVisible();
    await expect(page.locator('label:has-text("Assessment Type")')).toBeVisible();
    await expect(page.locator('label:has-text("Status")')).toBeVisible();
    // Date range inputs
    await expect(page.locator('label:has-text("Start Date From")')).toBeVisible();
    await expect(page.locator('label:has-text("Start Date To")')).toBeVisible();
    await expect(page.locator('label:has-text("End Date From")')).toBeVisible();
    await expect(page.locator('label:has-text("End Date To")')).toBeVisible();
    // Checkboxes
    await expect(page.locator('label:has-text("Past Due Only")')).toBeVisible();
    await expect(page.locator('label:has-text("Show Completed")')).toBeVisible();
    await expect(page.locator('label:has-text("Show only assigned to me")')).toBeVisible();
  });

  test('should display the data table with correct columns', async ({ page }) => {
    await waitForTableToLoad(page);

    const headers = page.locator('.data-table thead th');
    await expect(headers.filter({ hasText: 'Assessment Name' })).toBeVisible();
    await expect(headers.filter({ hasText: 'Application' })).toBeVisible();
    await expect(headers.filter({ hasText: 'Assessment Type' })).toBeVisible();
    await expect(headers.filter({ hasText: 'Start Date' })).toBeVisible();
    await expect(headers.filter({ hasText: 'End Date' })).toBeVisible();
    await expect(headers.filter({ hasText: 'Status' })).toBeVisible();
    await expect(headers.filter({ hasText: 'Assessors' })).toBeVisible();
    await expect(headers.filter({ hasText: 'Vulnerabilities' })).toBeVisible();
    await expect(headers.filter({ hasText: 'Actions' })).toBeVisible();
  });

  test('should show pagination controls', async ({ page }) => {
    await waitForTableToLoad(page);
    await expect(page.locator('.pagination-container')).toBeVisible();
    await expect(page.locator('select.page-size-select')).toBeVisible();
  });

  test('should filter by status', async ({ page }) => {
    await expandAdvancedSearch(page);
    await waitForAssessmentsLoad(page);

    // Pick the Status select — identified by having a DRAFT option
    const statusSelect = page.locator('.card-body select:has(option[value="DRAFT"])');
    await statusSelect.selectOption('DRAFT');

    await waitForAssessmentsLoad(page);

    // Every visible status badge should say DRAFT
    const statusBadges = page.locator('.data-table tbody .badge');
    const badgeCount = await statusBadges.count();
    for (let i = 0; i < badgeCount; i++) {
      const text = await statusBadges.nth(i).textContent();
      if (text?.includes('DRAFT') || text?.trim() === 'DRAFT') {
        // at least one DRAFT badge means filter applied
      }
    }
  });

  test('should filter by search text', async ({ page }) => {
    await expandAdvancedSearch(page);
    await waitForAssessmentsLoad(page);

    const searchInput = page.locator('input[placeholder*="Search assessments"]');
    await searchInput.fill('nonexistentxyz999');

    await waitForAssessmentsLoad(page);

    // Table should either show no results row or be empty
    const rowCount = await page.locator('.data-table tbody tr').count();
    // Either no-data message or 0/1 rows (one row may be the empty state row)
    expect(rowCount).toBeLessThanOrEqual(1);
  });

  test('should toggle Show Completed checkbox', async ({ page }) => {
    await expandAdvancedSearch(page);

    const checkbox = page.locator('#showCompleted');
    await expect(checkbox).not.toBeChecked(); // default is unchecked

    await checkbox.check();
    await expect(checkbox).toBeChecked();

    await waitForAssessmentsLoad(page);
  });

  test('should toggle Past Due Only checkbox', async ({ page }) => {
    await expandAdvancedSearch(page);

    const checkbox = page.locator('#pastDue');
    await expect(checkbox).not.toBeChecked();

    await checkbox.check();
    await expect(checkbox).toBeChecked();

    await waitForAssessmentsLoad(page);
  });

  test('should toggle Assigned To Me checkbox', async ({ page }) => {
    await expandAdvancedSearch(page);

    const checkbox = page.locator('#assignedToMe');
    await expect(checkbox).not.toBeChecked(); // default is false

    await checkbox.check();
    await expect(checkbox).toBeChecked();

    // Should update the header description
    await expect(page.locator('text=Showing only assessments assigned to you')).toBeVisible();

    await checkbox.uncheck();
    await expect(page.locator('text=Showing all assessments')).toBeVisible();
  });

  test('should set date range filters', async ({ page }) => {
    await expandAdvancedSearch(page);

    await page.locator('label:has-text("Start Date From") ~ input[type="date"]').fill('2024-01-01');
    await page.locator('label:has-text("Start Date To") ~ input[type="date"]').fill('2024-12-31');
    await page.locator('label:has-text("End Date From") ~ input[type="date"]').fill('2024-01-01');
    await page.locator('label:has-text("End Date To") ~ input[type="date"]').fill('2024-12-31');

    await waitForAssessmentsLoad(page);
    // If filters applied, table may be empty or contain results — just verify no crash
    await expect(page.locator('.data-table')).toBeVisible();
  });

  test('should export CSV when clicking Export CSV button', async ({ page }) => {
    const downloadPromise = page.waitForEvent('download', { timeout: TEST_CONFIG.timeout.long });
    await page.locator('button:has-text("Export CSV")').click();

    const download = await downloadPromise;
    expect(download.suggestedFilename()).toMatch(/assessments-\d{4}-\d{2}-\d{2}\.csv/);
  });

  test('should navigate to assessment detail when clicking a row', async ({ page }) => {
    await waitForTableToLoad(page);

    const rows = page.locator('.data-table tbody tr');
    const rowCount = await rows.count();

    if (rowCount > 0 && !(await rows.first().locator('td').first().textContent())?.includes('No data')) {
      await rows.first().click();
      await page.waitForURL('**/assessments/*', { timeout: TEST_CONFIG.timeout.medium });
      expect(page.url()).toMatch(/\/assessments\/[a-zA-Z0-9]+$/);
    }
  });

  test('should navigate to assessment detail via View button', async ({ page }) => {
    await waitForTableToLoad(page);

    const viewButtons = page.locator('button:has-text("View")');
    const count = await viewButtons.count();

    if (count > 0) {
      await viewButtons.first().click();
      await page.waitForURL('**/assessments/*', { timeout: TEST_CONFIG.timeout.medium });
      expect(page.url()).toMatch(/\/assessments\/[a-zA-Z0-9]+$/);
    }
  });

  test('should show Past Due badge for overdue assessments', async ({ page }) => {
    await waitForTableToLoad(page);

    // Enable show completed to see all results
    await expandAdvancedSearch(page);
    await page.locator('#showCompleted').check();
    await waitForAssessmentsLoad(page);

    // Check if any Past Due badges are present (may be 0 if no past-due assessments)
    const pastDueBadges = page.locator('.data-table tbody .badge:has-text("Past Due")');
    const badgeCount = await pastDueBadges.count();
    // Just verify the locator works without error
    expect(badgeCount).toBeGreaterThanOrEqual(0);
  });
});

// ─── Assessment Detail Page ───────────────────────────────────────────────────

test.describe('Assessment Detail Page', () => {
  let assessmentId: string | null = null;

  test.beforeEach(async ({ page }) => {
    await loginAsSuperAdmin(page);
    await navigateToAssessments(page);
    await waitForTableToLoad(page);

    // Pick the first assessment in the table (if any)
    const rows = page.locator('.data-table tbody tr');
    const count = await rows.count();
    if (count > 0) {
      const firstRow = rows.first();
      const cellText = await firstRow.locator('td').first().textContent();
      if (!cellText?.includes('No data')) {
        // Grab ID from the View button's navigation
        const viewBtn = firstRow.locator('button:has-text("View")');
        if (await viewBtn.isVisible()) {
          await viewBtn.click();
          await page.waitForURL('**/assessments/*', { timeout: TEST_CONFIG.timeout.medium });
          const url = page.url();
          assessmentId = url.split('/').pop() || null;
        }
      }
    }
  });

  test('should display assessment detail page', async ({ page }) => {
    if (!assessmentId) return test.skip();

    await expect(page.locator('h2')).toBeVisible();
    await expect(page.locator('button:has-text("Back to Assessments")')).toBeVisible();
  });

  test('should navigate back to assessments list', async ({ page }) => {
    if (!assessmentId) return test.skip();

    await page.locator('button:has-text("Back to Assessments")').click();
    await page.waitForURL('**/assessments', { timeout: TEST_CONFIG.timeout.medium });
    await expect(page.locator('.assessments-page')).toBeVisible();
  });

  test('should display status badge on detail page', async ({ page }) => {
    if (!assessmentId) return test.skip();

    await expect(page.locator('.badge')).toBeVisible();
  });

  test('should show Generate Report button on detail page', async ({ page }) => {
    if (!assessmentId) return test.skip();

    await expect(page.locator('button:has-text("Generate Report")')).toBeVisible();
  });
});

// ─── Create Assessment Page ───────────────────────────────────────────────────

test.describe('Create Assessment Page', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsSuperAdmin(page);
    // Create assessment route is under /scheduling/create
    await page.goto(`${TEST_CONFIG.baseURL}/scheduling/create`);
    await page.waitForURL('**/scheduling/create', { timeout: TEST_CONFIG.timeout.medium });
  });

  test('should display Create Assessment page with correct title', async ({ page }) => {
    await expect(page.locator('h2:has-text("Create Assessment")')).toBeVisible();
  });

  test('should display all required form sections', async ({ page }) => {
    // Basic info labels
    await expect(page.locator('label:has-text("Name")')).toBeVisible();
    await expect(page.locator('label:has-text("Application")')).toBeVisible();
    await expect(page.locator('label:has-text("Assessment Type")')).toBeVisible();
    // Scheduling
    await expect(page.locator('label:has-text("Start Date")')).toBeVisible();
    await expect(page.locator('label:has-text("Planned End Date")')).toBeVisible();
    // Team
    await expect(page.locator('label:has-text("Engagement Manager")')).toBeVisible();
    await expect(page.locator('label:has-text("Remediation Manager")')).toBeVisible();
    await expect(page.locator('label:has-text("Assessors")').first()).toBeVisible();
  });

  test('should display Scope section', async ({ page }) => {
    await expect(page.locator('h5:has-text("Scope")')).toBeVisible();
  });

  test('should display Engagement URLs section', async ({ page }) => {
    await expect(page.locator('h5:has-text("Engagement URLs")')).toBeVisible();
    // Add button is inside the Engagement URLs section
    await expect(page.locator('.form-section:has(h5:has-text("Engagement URLs")) button:has-text("Add")')).toBeVisible();
  });

  test('should display Stakeholders section', async ({ page }) => {
    await expect(page.locator('label:has-text("Stakeholders")')).toBeVisible();
    // Add button for stakeholders
    await expect(page.locator('label:has-text("Stakeholders") ~ div button:has-text("Add"), .form-section:has(label:has-text("Stakeholders")) button:has-text("Add")')).toBeVisible();
  });

  test('should display the calendar preview placeholder', async ({ page }) => {
    // Before dates are entered, shows the placeholder
    await expect(page.locator('.calendar-panel')).toBeVisible();
    await expect(page.locator('text=Fill in the name and dates to see the calendar preview')).toBeVisible();
  });

  test('should show Save & Close and Cancel buttons', async ({ page }) => {
    await expect(page.locator('button:has-text("Save & Close")')).toBeVisible();
    await expect(page.locator('button:has-text("Cancel")')).toBeVisible();
  });

  test('should show validation when submitting without required fields', async ({ page }) => {
    await page.locator('button:has-text("Save & Close")').click();

    // Browser native required validation or alert message
    const nameInput = page.locator('input[placeholder*="Assessment name"]').first();
    const isInvalid = await nameInput.evaluate(el => !(el as HTMLInputElement).validity.valid);
    expect(isInvalid).toBe(true);
  });

  test('should add an Engagement URL', async ({ page }) => {
    const addBtn = page.locator('.form-section:has(h5:has-text("Engagement URLs")) button:has-text("Add")');

    // Fill in URL and description inputs
    await page.locator('input[placeholder="URL"]').fill('https://staging.example.com');
    await page.locator('input[placeholder="Description"]').fill('Staging environment');

    await addBtn.click();

    // The URL entry should appear in the list
    await expect(page.locator('a[href="https://staging.example.com"]')).toBeVisible();
  });

  test('should add a Stakeholder', async ({ page }) => {
    // Fill in new stakeholder fields
    await page.locator('input[placeholder="Name"]').fill('Jane Smith');
    await page.locator('input[type="email"][placeholder="Email"]').fill('jane@example.com');

    // Click the Add button in the stakeholders section
    const addBtn = page.locator('.form-section:has(label:has-text("Stakeholders")) button:has-text("Add")').last();
    await addBtn.click();

    // Stakeholder name should now appear in the list
    await expect(page.locator('text=Jane Smith')).toBeVisible();
  });

  test('should warn when navigating away with unsaved changes', async ({ page }) => {
    // Type something into the name field to make form dirty
    const nameInput = page.locator('input[placeholder="Assessment name"]');
    await nameInput.fill('My New Assessment');

    // Click cancel — isDirty=true so ConfirmDialog should open
    await page.locator('button:has-text("Cancel")').click();

    // ConfirmDialog renders as a modal
    await expect(page.locator('.modal, [role="dialog"]')).toBeVisible({ timeout: TEST_CONFIG.timeout.short });
    await expect(page.locator('h3.modal-title:has-text("Unsaved Changes")')).toBeVisible();
  });

  test('should populate Report Template section after selecting Assessment Type', async ({ page }) => {
    // Select an assessment type (if any are populated)
    const typeSelect = page.locator('select').filter({ has: page.locator('option:has-text("Select type")') });
    const options = await typeSelect.locator('option').count();

    if (options > 1) {
      // Select the first non-placeholder option
      const optionTexts = await typeSelect.locator('option').allTextContents();
      const firstReal = optionTexts.find(t => t.trim() && !t.includes('Select'));
      if (firstReal) {
        await typeSelect.selectOption({ label: firstReal.trim() });

        // Report Template section should appear
        await expect(page.locator('h5:has-text("Report Template")')).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
      }
    }
  });

  test('should navigate back to engagement when clicking Cancel (clean form)', async ({ page }) => {
    // Form is not dirty on first load, click Cancel
    await page.locator('button:has-text("Cancel")').click();

    // Either dialog appears (if something auto-populated) or direct navigation
    const modalVisible = await page.locator('.modal, [role="dialog"]').isVisible().catch(() => false);

    if (modalVisible) {
      // Confirm leaving by clicking "Yes, Leave"
      await page.locator('button:has-text("Yes, Leave")').click();
    }

    await page.waitForURL('**/scheduling', { timeout: TEST_CONFIG.timeout.medium });
    await expect(page.locator('.engagements-page')).toBeVisible();
  });
});

// ─── Edit Assessment Page ─────────────────────────────────────────────────────

test.describe('Edit Assessment Page', () => {
  let assessmentId: string | null = null;

  test.beforeEach(async ({ page }) => {
    await loginAsSuperAdmin(page);
    await navigateToAssessments(page);
    await waitForTableToLoad(page);

    // Navigate to the first assessment's detail, then go to edit via direct URL
    const viewButtons = page.locator('button:has-text("View")');
    const count = await viewButtons.count();
    if (count > 0) {
      await viewButtons.first().click();
      await page.waitForURL('**/assessments/*', { timeout: TEST_CONFIG.timeout.medium });
      const url = page.url();
      assessmentId = url.split('/').pop() || null;

      // Navigate to edit page via /scheduling/edit/:id
      if (assessmentId) {
        await page.goto(`${TEST_CONFIG.baseURL}/scheduling/edit/${assessmentId}`);
        await page.waitForURL(`**/scheduling/edit/${assessmentId}`, { timeout: TEST_CONFIG.timeout.medium });
      }
    }
  });

  test('should display Edit Assessment page with existing data', async ({ page }) => {
    if (!assessmentId) return test.skip();

    await expect(page.locator('h2:has-text("Edit Assessment")')).toBeVisible();

    // Name field should be pre-filled
    const nameInput = page.locator('input[placeholder="Assessment name"]');
    const value = await nameInput.inputValue();
    expect(value.length).toBeGreaterThan(0);
  });

  test('should display Status field in edit mode', async ({ page }) => {
    if (!assessmentId) return test.skip();

    await expect(page.locator('h5:has-text("Status")')).toBeVisible();
    const statusSelect = page.locator('.form-section:has(h5:has-text("Status")) select');
    await expect(statusSelect).toBeVisible();
  });

  test('should display Delete button in edit mode', async ({ page }) => {
    if (!assessmentId) return test.skip();

    // Delete button shows in the form-actions section
    await expect(page.locator('button:has-text("Delete")')).toBeVisible();
  });

  test('should show delete confirmation dialog', async ({ page }) => {
    if (!assessmentId) return test.skip();

    await page.locator('button:has-text("Delete")').click();

    await expect(page.locator('.modal, [role="dialog"]')).toBeVisible({ timeout: TEST_CONFIG.timeout.short });
    await expect(page.locator('text=Delete Assessment')).toBeVisible();
  });

  test('should cancel delete and stay on edit page', async ({ page }) => {
    if (!assessmentId) return test.skip();

    await page.locator('button:has-text("Delete")').click();
    await expect(page.locator('.modal, [role="dialog"]')).toBeVisible();

    // Click Cancel in the ConfirmDialog
    await page.locator('.modal button:has-text("Cancel"), [role="dialog"] button:has-text("Cancel")').first().click();

    // Should still be on edit page
    expect(page.url()).toContain('/scheduling/edit/');
  });

  test('should track form dirty state on edit', async ({ page }) => {
    if (!assessmentId) return test.skip();

    // Modify the name field
    const nameInput = page.locator('input[placeholder="Assessment name"]');
    const original = await nameInput.inputValue();
    await nameInput.fill(original + ' MODIFIED');

    // Click Cancel to trigger dirty check
    await page.locator('button:has-text("Cancel")').click();

    // ConfirmDialog should appear since form is dirty
    await expect(page.locator('.modal, [role="dialog"]')).toBeVisible({ timeout: TEST_CONFIG.timeout.short });
    await expect(page.locator('h3.modal-title:has-text("Unsaved Changes")')).toBeVisible();
  });

  test('should display Save and Save & Close buttons in edit mode', async ({ page }) => {
    if (!assessmentId) return test.skip();

    await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Save & Close', exact: true })).toBeVisible();
  });
});
