import { test, expect } from '@playwright/test';
import { loginAsSuperAdmin, navigateToPage, waitForModal, waitForTableToLoad, TEST_CONFIG } from './helpers';

/**
 * A comment thread has to reflect comments this tab did not create: another user posting,
 * a workflow writing a system comment, or — since reply-by-email — the inbound poller
 * landing a reply up to ~90 seconds later with no browser action at all.
 *
 * The out-of-band comment here is posted through the API rather than a second browser, so
 * the test exercises exactly the case the UI cannot see coming.
 */
test.describe('Comment threads update without a reload', () => {
  test('an application comment posted elsewhere appears in an open thread', async ({ page }) => {
    await loginAsSuperAdmin(page);
    await navigateToPage(page, 'applications');
    await waitForTableToLoad(page);

    await page.click('button:has-text("Create Application")');
    await waitForModal(page);
    await page.fill('input[placeholder="Application Name"]', `LiveApp_${Date.now()}`);

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
    await expect(page.locator('.app-detail-chat-compose')).toBeVisible({
      timeout: TEST_CONFIG.timeout.medium,
    });

    const marker = `out-of-band-${Date.now()}`;
    await expect(page.getByText(marker)).toHaveCount(0);

    // Posted straight to the API — this tab has no idea it happened, exactly like a
    // comment arriving from the inbound mail poller.
    const status = await page.evaluate(async ({ id, text }) => {
      const res = await fetch(`/api/v1/applications/${id}/comments`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${localStorage.getItem('token')}`,
        },
        body: JSON.stringify({ content: `<p>${text}</p>` }),
      });
      return res.status;
    }, { id: appId, text: marker });
    expect(status).toBe(200);

    // The poll interval is 20s; allow for it plus the request.
    await expect(page.getByText(marker)).toBeVisible({ timeout: 40_000 });
  });
});
