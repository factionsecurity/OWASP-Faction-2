import { test, expect } from '@playwright/test';
import {
  loginAsSuperAdmin,
  navigateToPage,
  waitForModal,
  waitForTableToLoad,
  TEST_CONFIG,
} from './helpers';

/**
 * @mention autocomplete scoping
 *
 * Typing `@` offers a user picker, and inserting a mention makes the backend notify (and
 * email) that person. That is only meaningful where the content is addressed to someone,
 * so the picker is opt-in via RichTextEditor's `mentions` prop and enabled on exactly
 * three surfaces: application comments, vulnerability comments and assessment notes.
 *
 * These tests pin both halves of that contract on the application page, which carries a
 * mention-enabled editor (the comment box) and a mention-disabled one (the Basic
 * Information description) — reusable application metadata must never notify anyone.
 */

const MENTION_DROPDOWN = '.rte-mention-dropdown';

/** The picker fetches on a 150ms debounce, so a negative assertion has to outlast it. */
const DEBOUNCE_SETTLE_MS = 1500;

test.describe('@mention autocomplete scoping', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsSuperAdmin(page);
    await navigateToPage(page, 'applications');
    await waitForTableToLoad(page);

    await page.click('button:has-text("Create Application")');
    await waitForModal(page);
    // Name is the only required field; description is a RichTextEditor and stays empty.
    await page.fill('input[placeholder="Application Name"]', `MentionApp_${Date.now()}`);

    const [response] = await Promise.all([
      page.waitForResponse(
        r => r.url().includes('/applications') && r.request().method() === 'POST',
        { timeout: TEST_CONFIG.timeout.long },
      ),
      page.click('button[type="submit"]:has-text("Create")'),
    ]);

    const body = await response.json();
    const appId = body?.data?.id ?? body?.id;
    expect(appId, 'create-application response should carry the new id').toBeTruthy();

    await page.goto(`/applications/${appId}/edit`);
    await expect(page.locator('.app-detail-section').first()).toBeVisible({
      timeout: TEST_CONFIG.timeout.medium,
    });
  });

  test('offers user autocomplete in the application comment box', async ({ page }) => {
    await page.click('button.app-chat-compose-collapsed');

    const editor = page.locator('.app-detail-chat-compose-input .rte-body');
    await expect(editor).toBeVisible();
    await editor.click();
    await page.keyboard.type('@a');

    const dropdown = page.locator(MENTION_DROPDOWN);
    await expect(dropdown).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });

    // Entries come from the live user directory, so each row carries an @username.
    await expect(page.locator('.rte-mention-item').first()).toContainText('@');
  });

  test('inserting a mention writes the data-username span the backend parses', async ({ page }) => {
    await page.click('button.app-chat-compose-collapsed');

    const editor = page.locator('.app-detail-chat-compose-input .rte-body');
    await editor.click();
    await page.keyboard.type('@a');
    await expect(page.locator(MENTION_DROPDOWN)).toBeVisible({
      timeout: TEST_CONFIG.timeout.medium,
    });

    // Enter accepts the highlighted user.
    await page.keyboard.press('Enter');

    await expect(page.locator(MENTION_DROPDOWN)).toHaveCount(0);
    // MentionQueueService greps for exactly this attribute — it is the only thing the
    // frontend and backend agree on, so assert the serialised form, not just the text.
    const mention = editor.locator('span.mention[data-username]');
    await expect(mention).toHaveCount(1);
    await expect(mention).toHaveAttribute('contenteditable', 'false');
  });

  test('offers user autocomplete in the markdown view', async ({ page }) => {
    await page.click('button.app-chat-compose-collapsed');
    const compose = page.locator('.app-detail-chat-compose-input');
    await compose.locator('.rte-mode-tab:has-text("Markdown")').click();

    const source = compose.locator('.rte-markdown-body .cm-content');
    await expect(source).toBeVisible();
    await source.click();
    await page.keyboard.type('@a');

    await expect(page.locator(MENTION_DROPDOWN)).toBeVisible({
      timeout: TEST_CONFIG.timeout.medium,
    });

    // Enter must be intercepted by the picker rather than inserting a newline.
    await page.keyboard.press('Enter');
    await expect(page.locator(MENTION_DROPDOWN)).toHaveCount(0);

    // The document holds a raw <span data-username>, but it renders as a chip: the
    // source view shows "@username" and none of the surrounding markup.
    const chip = compose.locator('.cm-mention');
    await expect(chip).toHaveCount(1);
    await expect(chip).toHaveText(/^@\w+$/);
    await expect(source).not.toContainText('data-username');
    await expect(source).not.toContainText('<span');
  });

  test('a mention chip deletes as one unit in the markdown view', async ({ page }) => {
    // The chip hides ~90 characters of markup. Without atomicRanges the caret could sit
    // inside the hidden span and a single Backspace would shave one character off it,
    // corrupting the mention while the chip still looked intact.
    await page.click('button.app-chat-compose-collapsed');
    const compose = page.locator('.app-detail-chat-compose-input');
    await compose.locator('.rte-mode-tab:has-text("Markdown")').click();

    const source = compose.locator('.rte-markdown-body .cm-content');
    await source.click();
    await page.keyboard.type('@a');
    await expect(page.locator(MENTION_DROPDOWN)).toBeVisible({
      timeout: TEST_CONFIG.timeout.medium,
    });
    await page.keyboard.press('Enter');
    await expect(compose.locator('.cm-mention')).toHaveCount(1);

    // One Backspace clears the trailing space the picker inserts, the next takes the
    // whole mention — leaving no fragment of the span behind.
    await page.keyboard.press('Backspace');
    await page.keyboard.press('Backspace');

    await expect(compose.locator('.cm-mention')).toHaveCount(0);
    await expect(source).not.toContainText('span');
    await expect(source).not.toContainText('@');
  });

  test('offers user autocomplete in the split view', async ({ page }) => {
    await page.click('button.app-chat-compose-collapsed');
    const compose = page.locator('.app-detail-chat-compose-input');
    await compose.locator('.rte-mode-tab:has-text("Split")').click();

    const source = compose.locator('.rte-markdown-body .cm-content');
    await expect(source).toBeVisible();
    await source.click();
    await page.keyboard.type('@a');

    await expect(page.locator(MENTION_DROPDOWN)).toBeVisible({
      timeout: TEST_CONFIG.timeout.medium,
    });
  });

  test('switching to the markdown view preserves an existing mention', async ({ page }) => {
    // Regression: turndown unwrapped the mention span, so merely opening the markdown
    // view stripped data-username and the backend silently stopped notifying anyone.
    await page.click('button.app-chat-compose-collapsed');
    const compose = page.locator('.app-detail-chat-compose-input');

    const rich = compose.locator('.rte-body');
    await rich.click();
    await page.keyboard.type('@a');
    await expect(page.locator(MENTION_DROPDOWN)).toBeVisible({
      timeout: TEST_CONFIG.timeout.medium,
    });
    await page.keyboard.press('Enter');

    const username = await compose
      .locator('span.mention[data-username]')
      .getAttribute('data-username');
    expect(username).toBeTruthy();

    await compose.locator('.rte-mode-tab:has-text("Markdown")').click();
    // Rendered as a chip, so the assertion is on the chip rather than the raw markup —
    // the round trip back to rich below is what proves the span is still in the document.
    await expect(compose.locator('.cm-mention')).toHaveText(`@${username}`);

    // …and it must survive the trip back, not just the trip out.
    await compose.locator('.rte-mode-tab:has-text("Rich Text")').click();
    await expect(compose.locator(`span.mention[data-username="${username}"]`)).toHaveCount(1);
  });

  test('does not offer autocomplete in the application description', async ({ page }) => {
    const section = page.locator(
      '.app-detail-section:has(.form-section-title:text-is("Basic Information"))',
    );
    await section.locator('.app-detail-edit-btn').click();

    const editor = section.locator(
      '.form-group:has(label.form-label:text-is("Description")) .rte-body',
    );
    await expect(editor).toBeVisible();
    await editor.click();
    await page.keyboard.press('End');
    await page.keyboard.type(' @a');

    // The text must actually land — otherwise the negative assertion below is vacuous.
    await expect(editor).toContainText('@a');

    await page.waitForTimeout(DEBOUNCE_SETTLE_MS);
    await expect(page.locator(MENTION_DROPDOWN)).toHaveCount(0);
  });

  test('does not offer autocomplete in the description markdown view', async ({ page }) => {
    // The markdown view gates in cmMentionRange, a separate code path from the rich
    // view's detectMentionQuery, so the opt-out needs proving on both.
    const section = page.locator(
      '.app-detail-section:has(.form-section-title:text-is("Basic Information"))',
    );
    await section.locator('.app-detail-edit-btn').click();

    const group = section.locator(
      '.form-group:has(label.form-label:text-is("Description"))',
    );
    await group.locator('.rte-mode-tab:has-text("Markdown")').click();

    const source = group.locator('.rte-markdown-body .cm-content');
    await expect(source).toBeVisible();
    await source.click();
    await page.keyboard.type('@a');
    await expect(source).toContainText('@a');

    await page.waitForTimeout(DEBOUNCE_SETTLE_MS);
    await expect(page.locator(MENTION_DROPDOWN)).toHaveCount(0);
  });
});
