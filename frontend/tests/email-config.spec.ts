import { test, expect } from '@playwright/test';
import { loginAsSuperAdmin, TEST_CONFIG } from './helpers';

/**
 * The SMTP enable switch reads as a live on/off control, so it persists on toggle
 * instead of waiting for "Save Configuration". Before this, flipping it and navigating
 * away silently left email switched off — the failure that made mention emails look
 * broken even though the SMTP credentials were correct.
 *
 * The test flips whatever the current state is and restores it at the end, so it does
 * not leave a deployment's email configuration altered.
 */
test.describe('Email configuration', () => {
  const LABEL = '.email-toggle-label';

  test('the enable switch saves without pressing Save Configuration', async ({ page }) => {
    await loginAsSuperAdmin(page);
    await page.goto('/email-config');

    const label = page.locator(LABEL);
    await expect(label).toHaveText(/^(Enabled|Disabled)$/, {
      timeout: TEST_CONFIG.timeout.medium,
    });

    const wasEnabled = (await label.textContent())?.trim() === 'Enabled';
    const flipped = wasEnabled ? 'Disabled' : 'Enabled';
    const original = wasEnabled ? 'Enabled' : 'Disabled';

    const expectTogglePut = () =>
      page.waitForResponse(
        r => r.url().includes('/admin/email-config') && r.request().method() === 'PUT',
        { timeout: TEST_CONFIG.timeout.medium },
      );

    // The input is display:none, so drive the label the user actually clicks.
    const [res] = await Promise.all([expectTogglePut(), page.locator('.email-toggle').click()]);
    expect(res.status()).toBe(200);
    await expect(label).toHaveText(flipped);

    // The regression: reload without ever pressing Save Configuration.
    await page.reload();
    await expect(page.locator(LABEL)).toHaveText(flipped, {
      timeout: TEST_CONFIG.timeout.medium,
    });

    // Restore, and confirm the switch persists in both directions.
    const [restore] = await Promise.all([
      expectTogglePut(),
      page.locator('.email-toggle').click(),
    ]);
    expect(restore.status()).toBe(200);
    await page.reload();
    await expect(page.locator(LABEL)).toHaveText(original, {
      timeout: TEST_CONFIG.timeout.medium,
    });
  });
});
