import { test, expect, Page } from '@playwright/test';
import { loginAsSuperAdmin, navigateToPage, waitForTableToLoad } from './helpers';

/**
 * Server-side table sorting.
 *
 * The tables are paginated on the server, so sorting has to be too — ordering only the rows of the
 * current page would reorder 10 of N rows and call it sorted. These tests therefore assert on the
 * request the page issues (the `sort` query param) as well as the rendered result, since a page
 * that sorted locally would still look ordered on page 1.
 */

/** Header cells are buttons only when the column is sortable. */
const sortButton = (page: Page, header: string) =>
  page.locator('table thead th button.th-sort', { hasText: header }).first();

const columnValues = (page: Page, index: number) =>
  page.locator(`table tbody tr td:nth-child(${index + 1})`).allTextContents();

/** The `sort` query param of the next list request the page makes. */
async function sortParamOf(page: Page, urlPart: string, action: () => Promise<void>) {
  const [request] = await Promise.all([
    page.waitForRequest((r) => r.url().includes(urlPart) && r.url().includes('sort=')),
    action(),
  ]);
  return new URL(request.url()).searchParams.get('sort');
}

test.describe('Table sorting', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsSuperAdmin(page);
  });

  test('sortable headers are buttons; computed columns are not', async ({ page }) => {
    await navigateToPage(page, 'users');
    await waitForTableToLoad(page);

    await expect(sortButton(page, 'Email')).toBeVisible();
    await expect(sortButton(page, 'Login Method')).toBeVisible();

    // Roles and Teams render names resolved from id lists on the row, and Actions holds buttons —
    // none is a column the server can order by, so none offers a sort affordance.
    await expect(page.locator('table thead th button.th-sort', { hasText: 'Roles' })).toHaveCount(0);
    await expect(page.locator('table thead th button.th-sort', { hasText: 'Actions' })).toHaveCount(0);
  });

  test('clicking a header sends sort to the server and cycles asc → desc → unsorted', async ({ page }) => {
    await navigateToPage(page, 'users');
    await waitForTableToLoad(page);

    const email = sortButton(page, 'Email');

    expect(await sortParamOf(page, '/api/v1/users', () => email.click())).toBe('email,asc');
    await expect(page.locator('table thead th', { hasText: 'Email' })).toHaveAttribute('aria-sort', 'ascending');

    expect(await sortParamOf(page, '/api/v1/users', () => email.click())).toBe('email,desc');
    await expect(page.locator('table thead th', { hasText: 'Email' })).toHaveAttribute('aria-sort', 'descending');

    // Third click clears the sort — the request goes out without a sort param at all.
    const [cleared] = await Promise.all([
      page.waitForRequest((r) => r.url().includes('/api/v1/users?')),
      email.click(),
    ]);
    expect(new URL(cleared.url()).searchParams.get('sort')).toBeNull();
    await expect(page.locator('table thead th', { hasText: 'Email' })).toHaveAttribute('aria-sort', 'none');
  });

  test('rows come back in the requested order, case-insensitively', async ({ page }) => {
    await navigateToPage(page, 'users');
    await waitForTableToLoad(page);

    await sortButton(page, 'Email').click();
    await waitForTableToLoad(page);
    const asc = await columnValues(page, 1);

    const folded = asc.map((v) => v.trim().toLowerCase()).filter(Boolean);
    expect(folded).toEqual([...folded].sort());

    await sortButton(page, 'Email').click();
    await waitForTableToLoad(page);
    const desc = (await columnValues(page, 1)).map((v) => v.trim().toLowerCase()).filter(Boolean);
    expect(desc).toEqual([...desc].sort().reverse());
  });

  test('sorting resets to page 1', async ({ page }) => {
    await navigateToPage(page, 'users');
    await waitForTableToLoad(page);

    await page.locator('.pagination-number', { hasText: '2' }).first().click();
    await waitForTableToLoad(page);
    await expect(page.locator('.pagination-number.active')).toHaveText('2');

    // Re-sorting reshuffles the whole result set, so staying on page 2 would show an
    // arbitrary slice rather than the top of the newly sorted list.
    await sortButton(page, 'Email').click();
    await waitForTableToLoad(page);
    await expect(page.locator('.pagination-number.active')).toHaveText('1');
  });

  test('sorting a joined column orders by the related name, not its id', async ({ page }) => {
    await navigateToPage(page, 'assessments');
    await waitForTableToLoad(page);

    const param = await sortParamOf(page, '/api/v1/assessments',
      () => sortButton(page, 'Application').click());
    expect(param).toBe('applicationName,asc');

    await waitForTableToLoad(page);
    const names = (await columnValues(page, 1)).map((v) => v.trim().toLowerCase()).filter(Boolean);
    expect(names).toEqual([...names].sort());
  });
});
