import { test, expect, Page } from '@playwright/test';
import { loginAsSuperAdmin, TEST_CONFIG } from './helpers';

/**
 * Assessment Config → Workflows, against a running app.
 *
 * The editing test works on a throwaway copy of Default Workflow and deletes it at the end, so
 * nothing it changes outlives the test. Creating a workflow needs Custom Workflows, so the open
 * source edition skips that test.
 */
async function openWorkflowsTab(page: Page) {
  await page.goto('/assessment-config');
  await page.locator('.config-tab-btn', { hasText: 'Workflows' }).click();
  await expect(page.locator('.workflow-list-item').first()).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
}

test.describe('Workflows tab', () => {
  let createdWorkflowId: string | null = null;

  test.beforeEach(async ({ page }) => {
    await loginAsSuperAdmin(page);
    await openWorkflowsTab(page);
  });

  test('Default Workflow is listed first and cannot be archived or deleted', async ({ page }) => {
    const first = page.locator('.workflow-list-item').first();
    await expect(first.locator('.badge', { hasText: 'Default' })).toBeVisible();

    await first.click();
    const header = page.locator('.workflow-detail-header');
    await expect(header.locator('h3')).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
    await expect(header.getByRole('button', { name: 'Archive', exact: true })).toHaveCount(0);
    await expect(header.getByRole('button', { name: 'Delete', exact: true })).toHaveCount(0);
    await expect(page.locator('.wf-status-name-input').first()).toBeVisible();
  });

  test('a copied workflow can be renamed, edited, saved and deleted', async ({ page }) => {
    const newButton = page.getByRole('button', { name: 'New Workflow' });
    test.skip(await newButton.isDisabled(), 'Custom Workflows is not in this edition');

    const name = `E2E Workflow ${Date.now()}`;
    await newButton.click();
    const modal = page.locator('.modal');
    await modal.locator('input.form-input').first().fill(name);
    const [created] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith('/workflows') && r.request().method() === 'POST',
        { timeout: TEST_CONFIG.timeout.medium },
      ),
      modal.getByRole('button', { name: 'Create' }).click(),
    ]);
    expect(created.status()).toBe(201);
    createdWorkflowId = (await created.json()).data.id;
    await expect(page.locator('.workflow-detail-header h3')).toHaveText(name, { timeout: TEST_CONFIG.timeout.medium });

    // Rename the first assessment status and add a vulnerability status, then save.
    const firstStatus = page.locator('.wf-status-name-input').first();
    const original = await firstStatus.inputValue();
    const renamed = `${original} E2E`;
    // The editor autosaves a moment after the last change; nothing is pressed to save it.
    const autosaved = page.waitForResponse(
      (r) => r.url().includes('/workflows/') && r.request().method() === 'PUT',
      { timeout: TEST_CONFIG.timeout.medium },
    );
    await firstStatus.fill(renamed);
    await page.locator('.vs-add-row input').fill('Awaiting Vendor');
    await page.locator('.vs-add-row').getByRole('button', { name: 'Add' }).click();

    const saved = await autosaved;
    expect(saved.status()).toBe(200);
    await expect(page.locator('.workflow-editor-state')).toHaveText('Saved', {
      timeout: TEST_CONFIG.timeout.medium,
    });

    // Both edits survive a reload.
    await openWorkflowsTab(page);
    await page.locator('.workflow-list-item', { hasText: name }).click();
    await expect(page.locator('.workflow-detail-header h3')).toHaveText(name, { timeout: TEST_CONFIG.timeout.medium });
    await expect(page.locator('.wf-status-name-input').first()).toHaveValue(renamed);
    await expect(page.locator('input[aria-label="Vulnerability status name"]').last()).toHaveValue('Awaiting Vendor');

    // Unused, so it can be deleted.
    await page.locator('.workflow-detail-header').getByRole('button', { name: 'Delete' }).click();
    await page.locator('.modal').getByRole('button', { name: 'Delete' }).click();
    await expect(page.locator('.workflow-list-item', { hasText: name })).toHaveCount(0, {
      timeout: TEST_CONFIG.timeout.medium,
    });
    createdWorkflowId = null;
  });

  // A failed run must not leave its throwaway workflow in the shared database.
  test.afterEach(async ({ page }) => {
    if (!createdWorkflowId) return;
    const token = await page.evaluate(() => localStorage.getItem('token'));
    await page.request.delete(`${TEST_CONFIG.apiURL}/workflows/${createdWorkflowId}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    createdWorkflowId = null;
  });
});
