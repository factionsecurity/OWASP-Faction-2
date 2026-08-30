import { Page, expect } from '@playwright/test';

/**
 * Test configuration and credentials
 */
export const TEST_CONFIG = {
  baseURL: process.env.BASE_URL || 'http://localhost:3000',
  apiURL: process.env.API_URL || 'http://localhost:8080/api/v1',
  superadmin: {
    username: process.env.TEST_SUPERADMIN_USERNAME || 'admin',
    password: process.env.TEST_SUPERADMIN_PASSWORD || 'admin123',
  },
  timeout: {
    short: 5000,
    medium: 10000,
    long: 30000,
  },
};

/**
 * Login as superadmin
 */
export async function loginAsSuperAdmin(page: Page) {
  // Navigate directly to login page (avoid redirect from /)
  await page.goto('/login');

  // Wait for login form to be visible
  await expect(page.locator('input#username')).toBeVisible();

  // Fill in credentials
  await page.fill('input#username', TEST_CONFIG.superadmin.username);
  await page.fill('input#password', TEST_CONFIG.superadmin.password);

  // Click login button
  await page.click('button[type="submit"]');

  // Wait for redirect to dashboard
  await page.waitForURL('**/dashboard', { timeout: TEST_CONFIG.timeout.medium });

  // Verify we're logged in by checking for the dashboard layout
  await expect(page.locator('.dashboard-layout')).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
}

/**
 * Navigate to a specific page
 */
export async function navigateToPage(page: Page, pageName: 'users' | 'teams' | 'roles' | 'assessments' | 'applications' | 'organizations' | 'assessment-config' | 'report-designer') {
  // Map of pages to their menu labels
  const pageLabels: Record<string, string> = {
    'users': 'Users',
    'teams': 'Teams',
    'roles': 'Roles',
    'assessments': 'Assessments',
    'applications': 'Applications',
    'organizations': 'Organizations',
    'assessment-config': 'Assessment Config',
    'report-designer': 'Report Designer',
  };

  const label = pageLabels[pageName];

  // Check if page is in Administration submenu
  const adminPages = ['users', 'teams', 'roles', 'assessment-config', 'report-designer'];

  if (adminPages.includes(pageName)) {
    // Expand Administration menu if not already expanded
    const administrationButton = page.locator('button.nav-item:has-text("Administration")');
    const isExpanded = await page.locator('.nav-submenu').isVisible().catch(() => false);

    if (!isExpanded) {
      await administrationButton.click();
      // Wait for submenu to expand
      await expect(page.locator('.nav-submenu')).toBeVisible({ timeout: TEST_CONFIG.timeout.short });
    }

    // Click on the submenu item
    await page.locator(`.nav-subitem:has-text("${label}")`).click();
  } else {
    // Click on the top-level menu item
    await page.locator(`button.nav-item:has-text("${label}")`).first().click();
  }

  // Wait for navigation
  await page.waitForURL(`**/${pageName}`, { timeout: TEST_CONFIG.timeout.medium });

  // Wait for the page to load (just check for content area)
  await expect(page.locator('.content-area')).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
}

/**
 * Logout
 */
export async function logout(page: Page) {
  // Look for logout button in the top bar
  const logoutButton = page.locator('button.logout-button, button:has-text("Logout")');

  if (await logoutButton.isVisible()) {
    await logoutButton.click();
    // Wait for redirect to login
    await page.waitForURL('**/login', { timeout: TEST_CONFIG.timeout.short });
  }
}

/**
 * Wait for modal to be visible
 */
export async function waitForModal(page: Page) {
  await expect(page.locator('.modal-overlay')).toBeVisible();
  await expect(page.locator('.modal')).toBeVisible();
}

/**
 * Close modal
 */
export async function closeModal(page: Page) {
  await page.click('.modal-close, button:has-text("Cancel")');
  await expect(page.locator('.modal-overlay')).not.toBeVisible();
}

/**
 * Generate random string for unique test data
 */
export function generateRandomString(length: number = 8): string {
  const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
  let result = '';
  for (let i = 0; i < length; i++) {
    result += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return result;
}

/**
 * Generate unique test user data
 */
export function generateTestUser() {
  const randomId = generateRandomString(6);
  return {
    username: `testuser_${randomId}`,
    email: `testuser_${randomId}@faction.test`,
    firstName: `Test`,
    lastName: `User ${randomId}`,
    password: `TestPass123!`,
  };
}

/**
 * Generate unique test organization data
 */
export function generateTestOrganization() {
  const randomId = generateRandomString(6);
  return {
    name: `TestOrg_${randomId}`,
    description: `Test organization ${randomId} for automated testing`,
  };
}

/**
 * Wait for table to load
 */
export async function waitForTableToLoad(page: Page) {
  // Wait for table to be visible
  await expect(page.locator('table, .data-table')).toBeVisible();

  // Wait for any "Loading..." text to disappear
  const loadingText = page.locator('text="Loading..."');
  const isLoadingVisible = await loadingText.isVisible().catch(() => false);

  if (isLoadingVisible) {
    await loadingText.waitFor({ state: 'hidden', timeout: TEST_CONFIG.timeout.medium });
  }

  // Wait a bit for the table to fully render
  await page.waitForTimeout(200);
}

/**
 * Search in table
 * NOTE: Search only triggers after 3+ characters or when empty (to clear)
 */
export async function searchInTable(page: Page, searchTerm: string) {
  const searchInput = page.locator('input[placeholder*="Search"], input[type="search"]');

  // Set up listener for API request before triggering search
  const apiRequestPromise = page.waitForResponse(
    response => response.url().includes('/api/v1/') && response.status() === 200,
    { timeout: TEST_CONFIG.timeout.medium }
  );

  // Clear the input first
  await searchInput.clear();

  if (searchTerm) {
    // Type the search term (triggers onChange for each character)
    await searchInput.type(searchTerm, { delay: 50 });
  }

  // Wait for the API request to complete
  await apiRequestPromise.catch(() => {
    // API call might not happen if search is <3 chars and not empty
  });

  // Wait for table to finish loading
  await waitForTableToLoad(page);
}

/**
 * Get table row count
 */
export async function getTableRowCount(page: Page): Promise<number> {
  const rows = page.locator('table tbody tr, .data-table tbody tr');
  return await rows.count();
}

/**
 * Check if text exists in table
 */
export async function isTextInTable(page: Page, text: string): Promise<boolean> {
  const tableBody = page.locator('table tbody, .data-table tbody');

  // Wait for table to have content (not empty)
  await tableBody.locator('tr').first().waitFor({ state: 'visible', timeout: 5000 });

  // Get all text content from table body
  const tableText = await tableBody.textContent();

  if (!tableText) {
    return false;
  }

  // Check if the text exists (case-insensitive for more robust matching)
  return tableText.toLowerCase().includes(text.toLowerCase());
}

/**
 * Click action button in table row
 */
export async function clickActionButtonInRow(
  page: Page,
  rowIdentifier: string,
  action: 'edit' | 'delete' | 'manage'
) {
  // Find the row containing the identifier
  const row = page.locator(`table tbody tr:has-text("${rowIdentifier}"), .data-table tbody tr:has-text("${rowIdentifier}")`).first();

  // Find the action button
  let buttonSelector: string;
  switch (action) {
    case 'edit':
      buttonSelector = 'button[title="Edit"]';
      break;
    case 'delete':
      buttonSelector = 'button[title="Delete"]';
      break;
    case 'manage':
      buttonSelector = 'button[title*="Manage"]';
      break;
  }

  await row.locator(buttonSelector).click();
}

/**
 * Handle confirmation dialog
 */
export async function handleConfirmationDialog(page: Page, accept: boolean = true) {
  page.once('dialog', async dialog => {
    if (accept) {
      await dialog.accept();
    } else {
      await dialog.dismiss();
    }
  });
}

/**
 * Generate unique test report template data
 */
export function generateTestReportTemplate() {
  const randomId = generateRandomString(6);
  return {
    name: `Test Template ${randomId}`,
    description: `Test report template ${randomId} for automated testing`,
    field: {
      displayName: `Test Field ${randomId}`,
      variableName: `test_field_${randomId}`,
    },
  };
}

/**
 * Wait for auto-save to complete
 */
export async function waitForAutoSave(page: Page) {
  // Look for "Saving..." indicator
  const savingIndicator = page.locator('text="Saving..."');
  const isSavingVisible = await savingIndicator.isVisible().catch(() => false);

  if (isSavingVisible) {
    // Wait for saving indicator to disappear
    await savingIndicator.waitFor({ state: 'hidden', timeout: TEST_CONFIG.timeout.medium });
  }

  // Wait a bit more for debounced save to complete
  await page.waitForTimeout(1500);
}
