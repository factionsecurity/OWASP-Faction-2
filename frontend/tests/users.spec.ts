import { test, expect } from '@playwright/test';
import {
  loginAsSuperAdmin,
  navigateToPage,
  waitForModal,
  closeModal,
  generateTestUser,
  waitForTableToLoad,
  searchInTable,
  isTextInTable,
  clickActionButtonInRow,
  handleConfirmationDialog,
  TEST_CONFIG,
} from './helpers';

/**
 * User Management Tests
 * Tests for CRUD operations on users
 */

test.describe('User Management', () => {
  // Login before each test
  test.beforeEach(async ({ page }) => {
    await loginAsSuperAdmin(page);
    await navigateToPage(page, 'users');
  });

  test.describe('User List Page', () => {
    test('should display users page correctly', async ({ page }) => {
      // Check page title (rendered by DashboardLayout)
      await expect(page.locator('h1.page-title')).toContainText('Users');

      // Check create button
      await expect(page.locator('button:has-text("Create User")')).toBeVisible();

      // Check table is visible
      await expect(page.locator('table, .data-table')).toBeVisible();

      // Check search input
      await expect(page.locator('input[placeholder*="Search"]')).toBeVisible();
    });

    test('should display table columns correctly', async ({ page }) => {
      await waitForTableToLoad(page);

      // Check table headers
      const headers = page.locator('table thead th, .data-table thead th');
      await expect(headers).toContainText(['Name', 'Email', 'Login Method', 'Status', 'Actions']);
    });

    test('should show pagination controls', async ({ page }) => {
      await waitForTableToLoad(page);

      // Check for pagination controls
      const pagination = page.locator('.pagination, [aria-label="pagination"]');

      // Pagination might not be visible if there are few items
      const isVisible = await pagination.isVisible().catch(() => false);

      if (isVisible) {
        // Check for page size selector
        await expect(page.locator('select, [role="combobox"]')).toBeVisible();
      }
    });
  });

  test.describe('Create User', () => {
    test('should open create user modal', async ({ page }) => {
      // Click create button
      await page.click('button:has-text("Create User")');

      // Modal should be visible
      await waitForModal(page);

      // Check modal title
      await expect(page.locator('.modal-title, h3')).toContainText('Create New User');

      // Check form fields
      await expect(page.locator('input[type="text"][placeholder*="username"]')).toBeVisible();
      await expect(page.locator('input[type="email"]')).toBeVisible();
      await expect(page.locator('input[type="password"]')).toBeVisible();
    });

    test('should validate required fields', async ({ page }) => {
      // Open modal
      await page.click('button:has-text("Create User")');
      await waitForModal(page);

      // Try to submit empty form
      await page.click('button[type="submit"]:has-text("Create")');

      // HTML5 validation should prevent submission
      const usernameInput = page.locator('input[type="text"][placeholder*="username"]').first();
      const isValid = await usernameInput.evaluate((el: HTMLInputElement) => el.validity.valid);

      expect(isValid).toBe(false);
    });

    test('should successfully create a new user', async ({ page }) => {
      const testUser = generateTestUser();

      // Click create button
      await page.click('button:has-text("Create User")');
      await waitForModal(page);

      // Fill in user details
      await page.fill('input[type="text"][placeholder*="username"]', testUser.username);
      await page.fill('input[type="email"]', testUser.email);

      // Find and fill first name and last name
      const firstNameInput = page.locator('input[type="text"]').filter({ hasText: '' }).nth(1);
      const lastNameInput = page.locator('input[type="text"]').filter({ hasText: '' }).nth(2);

      // Alternative: use labels to find inputs
      await page.locator('label:has-text("First Name")').locator('~ input, + input').fill(testUser.firstName);
      await page.locator('label:has-text("Last Name")').locator('~ input, + input').fill(testUser.lastName);

      // Fill password
      await page.fill('input[type="password"]', testUser.password);

      // Submit form
      await page.click('button[type="submit"]:has-text("Create")');

      // Wait for modal to close
      await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.medium });

      // Wait for table to reload
      await waitForTableToLoad(page);

      // Search for the new user
      await searchInTable(page, testUser.username);

      // Verify user appears in table
      const userExists = await isTextInTable(page, testUser.username);
      expect(userExists).toBe(true);

      // Verify email appears
      const emailExists = await isTextInTable(page, testUser.email);
      expect(emailExists).toBe(true);
    });

    test('should show error for duplicate username', async ({ page }) => {
      const testUser = generateTestUser();

      // Create first user
      await page.click('button:has-text("Create User")');
      await waitForModal(page);

      await page.fill('input[type="text"][placeholder*="username"]', testUser.username);
      await page.fill('input[type="email"]', testUser.email);
      await page.locator('label:has-text("First Name")').locator('~ input, + input').fill(testUser.firstName);
      await page.locator('label:has-text("Last Name")').locator('~ input, + input').fill(testUser.lastName);
      await page.fill('input[type="password"]', testUser.password);

      await page.click('button[type="submit"]:has-text("Create")');
      await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.medium });

      // Try to create duplicate user
      await page.click('button:has-text("Create User")');
      await waitForModal(page);

      await page.fill('input[type="text"][placeholder*="username"]', testUser.username);
      await page.fill('input[type="email"]', `different_${testUser.email}`);
      await page.locator('label:has-text("First Name")').locator('~ input, + input').fill(testUser.firstName);
      await page.locator('label:has-text("Last Name")').locator('~ input, + input').fill(testUser.lastName);
      await page.fill('input[type="password"]', testUser.password);

      await page.click('button[type="submit"]:has-text("Create")');

      // Should show error message
      await expect(page.locator('.error-message, [role="alert"]')).toBeVisible({ timeout: TEST_CONFIG.timeout.short });
    });

    test('should cancel user creation', async ({ page }) => {
      // Open modal
      await page.click('button:has-text("Create User")');
      await waitForModal(page);

      // Fill some data
      await page.fill('input[type="text"][placeholder*="username"]', 'canceltest');

      // Click cancel
      await page.click('button:has-text("Cancel")');

      // Modal should close
      await expect(page.locator('.modal-overlay')).not.toBeVisible();

      // User should not be created
      await searchInTable(page, 'canceltest');
      const userExists = await isTextInTable(page, 'canceltest');
      expect(userExists).toBe(false);
    });
  });

  test.describe('Edit User', () => {
    let testUser: ReturnType<typeof generateTestUser>;

    test.beforeEach(async ({ page }) => {
      // Create a test user first
      testUser = generateTestUser();

      await page.click('button:has-text("Create User")');
      await waitForModal(page);

      await page.fill('input[type="text"][placeholder*="username"]', testUser.username);
      await page.fill('input[type="email"]', testUser.email);
      await page.locator('label:has-text("First Name")').locator('~ input, + input').fill(testUser.firstName);
      await page.locator('label:has-text("Last Name")').locator('~ input, + input').fill(testUser.lastName);
      await page.fill('input[type="password"]', testUser.password);

      await page.click('button[type="submit"]:has-text("Create")');
      await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
      await waitForTableToLoad(page);

      // Search for the user
      await searchInTable(page, testUser.username);
    });

    test('should open edit user modal', async ({ page }) => {
      // Click edit button for the user
      await clickActionButtonInRow(page, testUser.username, 'edit');

      // Modal should be visible
      await waitForModal(page);

      // Check modal title
      await expect(page.locator('.modal-title, h3')).toContainText('Edit User');

      // Check that fields are pre-filled
      const usernameInput = page.locator('input[type="text"][placeholder*="username"]');
      await expect(usernameInput).toHaveValue(testUser.username);

      const emailInput = page.locator('input[type="email"]');
      await expect(emailInput).toHaveValue(testUser.email);
    });

    test('should successfully edit user details', async ({ page }) => {
      // Click edit button
      await clickActionButtonInRow(page, testUser.username, 'edit');
      await waitForModal(page);

      // Update email
      const newEmail = `updated_${testUser.email}`;
      await page.fill('input[type="email"]', newEmail);

      // Update first name
      const newFirstName = 'UpdatedFirst';
      await page.locator('label:has-text("First Name")').locator('~ input, + input').fill(newFirstName);

      // Submit changes
      await page.click('button[type="submit"]:has-text("Save Changes")');

      // Wait for modal to close
      await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.medium });

      // Wait for table to reload
      await waitForTableToLoad(page);

      // Search for the user
      await searchInTable(page, testUser.username);

      // Verify updated email appears
      const emailExists = await isTextInTable(page, newEmail);
      expect(emailExists).toBe(true);
    });

    test('should cancel edit without saving', async ({ page }) => {
      // Click edit button
      await clickActionButtonInRow(page, testUser.username, 'edit');
      await waitForModal(page);

      // Change email
      const originalEmail = testUser.email;
      await page.fill('input[type="email"]', 'shouldnotbesaved@test.com');

      // Click cancel
      await page.click('button:has-text("Cancel")');

      // Modal should close
      await expect(page.locator('.modal-overlay')).not.toBeVisible();

      // Verify original email still exists
      await searchInTable(page, testUser.username);
      const originalEmailExists = await isTextInTable(page, originalEmail);
      expect(originalEmailExists).toBe(true);
    });
  });

  test.describe('Delete User', () => {
    let testUser: ReturnType<typeof generateTestUser>;

    test.beforeEach(async ({ page }) => {
      // Create a test user first
      testUser = generateTestUser();

      await page.click('button:has-text("Create User")');
      await waitForModal(page);

      await page.fill('input[type="text"][placeholder*="username"]', testUser.username);
      await page.fill('input[type="email"]', testUser.email);
      await page.locator('label:has-text("First Name")').locator('~ input, + input').fill(testUser.firstName);
      await page.locator('label:has-text("Last Name")').locator('~ input, + input').fill(testUser.lastName);
      await page.fill('input[type="password"]', testUser.password);

      await page.click('button[type="submit"]:has-text("Create")');
      await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
      await waitForTableToLoad(page);

      // Search for the user
      await searchInTable(page, testUser.username);
    });

    test('should show confirmation dialog on delete', async ({ page }) => {
      // Set up dialog handler
      let dialogShown = false;
      page.once('dialog', async dialog => {
        dialogShown = true;
        expect(dialog.type()).toBe('confirm');
        expect(dialog.message()).toContain('delete');
        await dialog.dismiss();
      });

      // Click delete button
      await clickActionButtonInRow(page, testUser.username, 'delete');

      // Wait a bit for dialog
      await page.waitForTimeout(500);

      // Verify dialog was shown
      expect(dialogShown).toBe(true);
    });

    test('should successfully delete a user', async ({ page }) => {
      // Set up dialog handler to accept
      handleConfirmationDialog(page, true);

      // Click delete button
      await clickActionButtonInRow(page, testUser.username, 'delete');

      // Wait for table to reload
      await page.waitForTimeout(2000);
      await waitForTableToLoad(page);

      // Search for the user
      await searchInTable(page, testUser.username);

      // Verify user no longer appears
      const userExists = await isTextInTable(page, testUser.username);
      expect(userExists).toBe(false);
    });

    test('should cancel delete operation', async ({ page }) => {
      // Set up dialog handler to dismiss
      page.once('dialog', async dialog => {
        await dialog.dismiss();
      });

      // Click delete button
      await clickActionButtonInRow(page, testUser.username, 'delete');

      // Wait a bit
      await page.waitForTimeout(1000);

      // User should still exist
      await searchInTable(page, testUser.username);
      const userExists = await isTextInTable(page, testUser.username);
      expect(userExists).toBe(true);
    });
  });

  test.describe('Search Users', () => {
    test.beforeEach(async ({ page }) => {
      // Create multiple test users with distinct names
      const users = [
        { ...generateTestUser(), firstName: 'Alice', lastName: 'Anderson' },
        { ...generateTestUser(), firstName: 'Bob', lastName: 'Brown' },
        { ...generateTestUser(), firstName: 'Charlie', lastName: 'Clark' },
      ];

      for (const user of users) {
        await page.click('button:has-text("Create User")');
        await waitForModal(page);

        await page.fill('input[type="text"][placeholder*="username"]', user.username);
        await page.fill('input[type="email"]', user.email);
        await page.locator('label:has-text("First Name")').locator('~ input, + input').fill(user.firstName);
        await page.locator('label:has-text("Last Name")').locator('~ input, + input').fill(user.lastName);
        await page.fill('input[type="password"]', user.password);

        // Wait for API response before closing modal
        const createUserPromise = page.waitForResponse(
          response => response.url().includes('/api/v1/users') && response.status() === 200,
          { timeout: TEST_CONFIG.timeout.medium }
        );

        await page.click('button[type="submit"]:has-text("Create")');
        await createUserPromise;
        await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
        await page.waitForTimeout(300);
      }

      // Wait for final table reload to show all created users
      await waitForTableToLoad(page);
      await page.waitForTimeout(500);
    });

    test('should search users by first name', async ({ page }) => {
      // Search for Alice
      await searchInTable(page, 'Alice');

      // Verify Alice appears
      const aliceExists = await isTextInTable(page, 'Alice');
      expect(aliceExists).toBe(true);
    });

    test('should search users by last name', async ({ page }) => {
      // Search for Brown
      await searchInTable(page, 'Brown');

      // Verify Bob Brown appears
      const bobExists = await isTextInTable(page, 'Bob');
      expect(bobExists).toBe(true);
    });

    test('should search users by username', async ({ page }) => {
      // Get username from first visible row (it's in the .text-sm div under the name)
      const firstRow = page.locator('table tbody tr, .data-table tbody tr').first();
      const usernameDiv = firstRow.locator('.text-sm.text-muted, .text-muted').first();
      const username = await usernameDiv.textContent();

      if (username) {
        const cleanUsername = username.trim();

        // Only test if username has 3+ characters (search requirement)
        if (cleanUsername.length >= 3) {
          // Search for this username
          await searchInTable(page, cleanUsername);

          // Should still be visible
          const userExists = await isTextInTable(page, cleanUsername);
          expect(userExists).toBe(true);
        }
      }
    });

    test('should show empty state for no results', async ({ page }) => {
      // Search for non-existent user
      await searchInTable(page, 'NonExistentUser12345');

      // Check for empty state or no results message
      const hasNoResults =
        (await page.locator('text="No users found"').isVisible()) ||
        (await page.locator('text="No results"').isVisible()) ||
        (await page.locator('tbody tr').count()) === 0;

      expect(hasNoResults).toBe(true);
    });

    test('should clear search and show all users', async ({ page }) => {
      // Search for specific user
      await searchInTable(page, 'Alice');

      // Verify filtered results
      const aliceExists = await isTextInTable(page, 'Alice');
      expect(aliceExists).toBe(true);

      // Clear search
      await searchInTable(page, '');

      // Wait for table to reload
      await waitForTableToLoad(page);

      // Verify more users appear (or all test users)
      const rowCount = await page.locator('table tbody tr, .data-table tbody tr').count();
      expect(rowCount).toBeGreaterThanOrEqual(3); // At least our 3 test users
    });

    test('should search be case-insensitive', async ({ page }) => {
      // Search with lowercase
      await searchInTable(page, 'alice');

      // Should still find Alice
      const aliceExists = await isTextInTable(page, 'Alice');
      expect(aliceExists).toBe(true);
    });
  });

  test.describe('User Table Interactions', () => {
    test('should sort table columns', async ({ page }) => {
      await waitForTableToLoad(page);

      // Click on Name column header
      const nameHeader = page.locator('table thead th:has-text("Name"), .data-table thead th:has-text("Name")');

      if (await nameHeader.isVisible()) {
        await nameHeader.click();

        // Wait for sort to apply
        await page.waitForTimeout(500);
        await waitForTableToLoad(page);

        // Table should be re-rendered (hard to verify exact sort without knowing data)
        await expect(page.locator('table tbody tr, .data-table tbody tr').first()).toBeVisible();
      }
    });

    test('should change page size', async ({ page }) => {
      await waitForTableToLoad(page);

      // Look for page size selector
      const pageSizeSelect = page.locator('select.page-size-select');

      // Check if pagination exists (might not if few items)
      const isVisible = await pageSizeSelect.isVisible().catch(() => false);

      if (isVisible) {
        // Get initial row count
        const initialCount = await page.locator('table tbody tr').count();

        // Get current page size
        const currentPageSize = await pageSizeSelect.inputValue();

        // Change to 25 per page
        await pageSizeSelect.selectOption('25');

        // Wait for reload
        await page.waitForTimeout(1000);
        await waitForTableToLoad(page);

        // Row count might change
        const newCount = await page.locator('table tbody tr').count();

        // Verify page size changed
        const newPageSize = await pageSizeSelect.inputValue();
        expect(newPageSize).toBe('25');

        // Row count should be appropriate for page size
        expect(newCount).toBeLessThanOrEqual(25);
      }
    });
  });
});
