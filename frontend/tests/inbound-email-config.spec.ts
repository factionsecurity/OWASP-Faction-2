import { test, expect } from '@playwright/test';
import { loginAsSuperAdmin, TEST_CONFIG } from './helpers';

/**
 * Inbound email (reply-by-email) configuration.
 *
 * The reply address is what makes a mention email answerable: MentionEmailSender only
 * attaches a per-thread `Reply-To` when this is set, so it has to be editable in the
 * platform rather than through an env var and a restart.
 *
 * Each test restores whatever it changed, so running the suite does not leave a
 * deployment's inbound email configuration altered.
 */
test.describe('Inbound email configuration', () => {
  // Serial, not parallel: every test here mutates the same singleton config row, so
  // concurrent workers fight over `enabled` and the reply address and each other's
  // restore steps. This is shared global state, not per-test fixtures.
  test.describe.configure({ mode: 'serial' });

  const LABEL = '.email-toggle-label';
  // Scoped by its label: the Username field below shares the same placeholder, so
  // matching on placeholder alone is a strict-mode violation.
  const REPLY_INPUT = '.form-group:has(label.form-label:text-is("Reply Address")) input';

  test.beforeEach(async ({ page }) => {
    await loginAsSuperAdmin(page);
    await page.goto('/inbound-email-config');
    await expect(page.locator('.email-config-card').first()).toBeVisible({
      timeout: TEST_CONFIG.timeout.medium,
    });
  });

  const expectConfigPut = (page: import('@playwright/test').Page) =>
    page.waitForResponse(
      r => r.url().includes('/admin/inbound-email-config') && r.request().method() === 'PUT',
      { timeout: TEST_CONFIG.timeout.medium },
    );

  test('the enable switch saves without pressing Save Configuration', async ({ page }) => {
    const label = page.locator(LABEL);
    await expect(label).toHaveText(/^(Enabled|Disabled)$/);

    const wasEnabled = (await label.textContent())?.trim() === 'Enabled';
    const flipped = wasEnabled ? 'Disabled' : 'Enabled';
    const original = wasEnabled ? 'Enabled' : 'Disabled';

    const [res] = await Promise.all([expectConfigPut(page), page.locator('.email-toggle').click()]);
    expect(res.status()).toBe(200);
    await expect(label).toHaveText(flipped);

    await page.reload();
    await expect(page.locator(LABEL)).toHaveText(flipped, { timeout: TEST_CONFIG.timeout.medium });

    // Restore.
    await Promise.all([expectConfigPut(page), page.locator('.email-toggle').click()]);
    await page.reload();
    await expect(page.locator(LABEL)).toHaveText(original, { timeout: TEST_CONFIG.timeout.medium });
  });

  test('the reply address persists and shows the plus-addressed form', async ({ page }) => {
    const input = page.locator(REPLY_INPUT);
    const original = (await input.inputValue()) || '';

    await input.fill('faction-test@example.com');

    // The token is plus-addressed onto the local part, so surface the real shape rather
    // than making an admin infer why their mailbox needs sub-addressing.
    await expect(page.locator('code', { hasText: 'faction-test+' })).toBeVisible();

    const [res] = await Promise.all([
      expectConfigPut(page),
      page.click('button:has-text("Save Configuration")'),
    ]);
    expect(res.status()).toBe(200);
    await expect(page.locator('.email-config-saved')).toBeVisible();

    await page.reload();
    await expect(page.locator(REPLY_INPUT)).toHaveValue('faction-test@example.com', {
      timeout: TEST_CONFIG.timeout.medium,
    });

    // Restore.
    await page.locator(REPLY_INPUT).fill(original);
    await Promise.all([expectConfigPut(page), page.click('button:has-text("Save Configuration")')]);
  });

  test('warns when inbound is on but no reply address is set', async ({ page }) => {
    // The dangerous silent state: inbound enabled, yet mention emails stay notify-only
    // because there is nowhere for a reply to land.
    const input = page.locator(REPLY_INPUT);
    const originalAddress = (await input.inputValue()) || '';
    const wasEnabled = (await page.locator(LABEL).textContent())?.trim() === 'Enabled';

    await input.fill('');
    await Promise.all([expectConfigPut(page), page.click('button:has-text("Save Configuration")')]);

    if (!wasEnabled) {
      await Promise.all([expectConfigPut(page), page.locator('.email-toggle').click()]);
    }

    await expect(page.getByText('no reply address is set', { exact: false })).toBeVisible();

    // Restore both fields.
    if (!wasEnabled) {
      await Promise.all([expectConfigPut(page), page.locator('.email-toggle').click()]);
    }
    await page.locator(REPLY_INPUT).fill(originalAddress);
    await Promise.all([expectConfigPut(page), page.click('button:has-text("Save Configuration")')]);
  });

  test('the IMAP password is never sent to the browser', async ({ page }) => {
    const body = await page.evaluate(async () => {
      const res = await fetch('/api/v1/admin/inbound-email-config', {
        headers: { Authorization: `Bearer ${localStorage.getItem('token')}` },
      });
      return res.text();
    });

    // Either unset (null) or masked — never the stored secret.
    expect(body).toMatch(/"password":(null|"•+")/);
  });

  test('test-connection reports a missing host rather than failing silently', async ({ page }) => {
    const host = page.locator('input[placeholder="imap.yourcompany.com"]');
    const originalHost = (await host.inputValue()) || '';
    if (originalHost) test.skip(true, 'A host is configured; not clearing a real setting.');

    await page.click('button:has-text("Test IMAP Connection")');
    await expect(page.locator('.email-test-result--error')).toContainText(
      'IMAP host is not configured',
      { timeout: TEST_CONFIG.timeout.medium },
    );
  });
});
