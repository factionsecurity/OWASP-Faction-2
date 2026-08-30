import { test, expect } from '@playwright/test';
import { loginAsSuperAdmin, navigateToPage, waitForModal, waitForTableToLoad, TEST_CONFIG } from './helpers';

/**
 * Thread membership on an application, mirroring the vulnerability side. Everyone on the
 * list is notified and emailed when a comment is posted.
 *
 * The application Discussion is the stakeholder-facing thread, so it is the one most likely
 * to have someone on the other end — and it previously had no membership concept at all,
 * meaning only @mentions ever emailed anyone there.
 */
test.describe('Application thread subscribers', () => {
  test('assign me, leave, and membership persists across a reload', async ({ page }) => {
    await loginAsSuperAdmin(page);
    await navigateToPage(page, 'applications');
    await waitForTableToLoad(page);

    await page.click('button:has-text("Create Application")');
    await waitForModal(page);
    await page.fill('input[placeholder="Application Name"]', `SubsApp_${Date.now()}`);

    const [created] = await Promise.all([
      page.waitForResponse(
        r => r.url().includes('/applications') && r.request().method() === 'POST',
        { timeout: TEST_CONFIG.timeout.long },
      ),
      page.click('button[type="submit"]:has-text("Create")'),
    ]);
    const appId = (await created.json())?.data?.id;
    expect(appId).toBeTruthy();

    await page.goto(`/applications/${appId}/edit`);

    const panel = page.locator('.thread-subs');
    await expect(panel).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
    // A brand-new application has nobody following it yet.
    await expect(page.locator('.thread-subs-empty')).toBeVisible();

    const [added] = await Promise.all([
      page.waitForResponse(
        r => r.url().includes('/subscribers') && r.request().method() === 'POST',
        { timeout: TEST_CONFIG.timeout.medium },
      ),
      panel.locator('button:has-text("Assign me")').click(),
    ]);
    expect(added.status()).toBe(200);
    await expect(panel.locator('.thread-subs-chip')).toHaveCount(1);
    // The button flips, so the same control both joins and leaves.
    await expect(panel.locator('button:has-text("Leave")')).toBeVisible();

    // Membership is server-side, not local state.
    await page.reload();
    await expect(page.locator('.thread-subs-chip')).toHaveCount(1, {
      timeout: TEST_CONFIG.timeout.medium,
    });

    const [removed] = await Promise.all([
      page.waitForResponse(
        r => r.url().includes('/subscribers') && r.request().method() === 'DELETE',
        { timeout: TEST_CONFIG.timeout.medium },
      ),
      page.locator('.thread-subs button:has-text("Leave")').click(),
    ]);
    expect(removed.status()).toBe(200);
    await expect(page.locator('.thread-subs-empty')).toBeVisible();
  });

  test('commenting subscribes the author, so the thread is not one-way', async ({ page }) => {
    await loginAsSuperAdmin(page);
    await navigateToPage(page, 'applications');
    await waitForTableToLoad(page);

    await page.click('button:has-text("Create Application")');
    await waitForModal(page);
    await page.fill('input[placeholder="Application Name"]', `SubsApp2_${Date.now()}`);

    const [created] = await Promise.all([
      page.waitForResponse(
        r => r.url().includes('/applications') && r.request().method() === 'POST',
        { timeout: TEST_CONFIG.timeout.long },
      ),
      page.click('button[type="submit"]:has-text("Create")'),
    ]);
    const appId = (await created.json())?.data?.id;

    await page.goto(`/applications/${appId}/edit`);
    await expect(page.locator('.thread-subs-empty')).toBeVisible({
      timeout: TEST_CONFIG.timeout.medium,
    });

    await page.click('button.app-chat-compose-collapsed');
    const editor = page.locator('.app-detail-chat-compose-input .rte-body');
    await editor.click();
    await page.keyboard.type('Kicking off the review.');

    await Promise.all([
      page.waitForResponse(
        r => r.url().includes('/comments') && r.request().method() === 'POST',
        { timeout: TEST_CONFIG.timeout.medium },
      ),
      page.click('.app-chat-compose-send, button:has-text("Send")'),
    ]);

    // Whoever speaks on a thread is on it — otherwise the person they mentioned would be
    // subscribed while they were not, and they would never see the reply.
    await page.reload();
    await expect(page.locator('.thread-subs-chip')).toHaveCount(1, {
      timeout: TEST_CONFIG.timeout.medium,
    });
  });
});
