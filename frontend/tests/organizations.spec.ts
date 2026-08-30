import { test, expect } from '@playwright/test';
import {
  loginAsSuperAdmin,
  navigateToPage,
  waitForModal,
  generateTestOrganization,
  waitForTableToLoad,
  searchInTable,
  isTextInTable,
  clickActionButtonInRow,
  TEST_CONFIG,
} from './helpers';

/**
 * Organization Management Tests
 * Tests for CRUD operations on organizations
 */

test.describe('Organization Management', () => {
  // Login before each test
  test.beforeEach(async ({ page }) => {
    await loginAsSuperAdmin(page);
    await navigateToPage(page, 'organizations');
  });

  test.describe('Organization List Page', () => {
    test('should display organizations page correctly', async ({ page }) => {
      // Wait for page to fully load
      await waitForTableToLoad(page);

      // Check page title
      await expect(page.locator('h1')).toContainText('Organizations');

      // Check create button
      await expect(page.locator('button:has-text("Create Organization")')).toBeVisible();

      // Check table is visible
      await expect(page.locator('table, .data-table')).toBeVisible();

      // Check search input
      await expect(page.locator('input[placeholder*="Search"]')).toBeVisible();
    });

    test('should display table columns correctly', async ({ page }) => {
      await waitForTableToLoad(page);

      // Check table headers
      const headers = page.locator('table thead th, .data-table thead th');
      await expect(headers).toContainText(['Name', 'Description', 'Actions']);
    });

    test('should show pagination controls', async ({ page }) => {
      await waitForTableToLoad(page);

      // Check for page size selector
      await expect(page.locator('select.page-size-select')).toBeVisible();

      // Check for pagination info
      await expect(page.locator('.pagination-info')).toBeVisible();
    });
  });

  test.describe('Create Organization', () => {
    test('should open create organization modal', async ({ page }) => {
      // Click create button
      await page.click('button:has-text("Create Organization")');

      // Modal should be visible
      await waitForModal(page);

      // Check modal title
      await expect(page.locator('.modal-title, h3, h2')).toContainText('Create Organization');

      // Check form fields
      await expect(page.locator('input#name')).toBeVisible();
      await expect(page.locator('textarea#description')).toBeVisible();
    });

    test('should validate required fields', async ({ page }) => {
      // Open modal
      await page.click('button:has-text("Create Organization")');
      await waitForModal(page);

      // Try to submit empty form
      await page.click('button[type="submit"]:has-text("Create")');

      // HTML5 validation should prevent submission
      const nameInput = page.locator('input#name');
      const isValid = await nameInput.evaluate((el: HTMLInputElement) => el.validity.valid);

      expect(isValid).toBe(false);
    });

    test('should successfully create a new organization', async ({ page }) => {
      const testOrg = generateTestOrganization();

      // Click create button
      await page.click('button:has-text("Create Organization")');
      await waitForModal(page);

      // Fill in organization details
      await page.fill('input#name', testOrg.name);
      await page.fill('textarea#description', testOrg.description);

      // Submit form
      await page.click('button[type="submit"]:has-text("Create")');

      // Wait for modal to close
      await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.medium });

      // Wait for table to reload
      await waitForTableToLoad(page);

      // Search for the new organization
      await searchInTable(page, testOrg.name);

      // Verify organization appears in table
      const orgExists = await isTextInTable(page, testOrg.name);
      expect(orgExists).toBe(true);

      // Verify description appears
      const descExists = await isTextInTable(page, testOrg.description);
      expect(descExists).toBe(true);
    });

    test('should show error for duplicate organization name', async ({ page }) => {
      const testOrg = generateTestOrganization();

      // Create first organization
      await page.click('button:has-text("Create Organization")');
      await waitForModal(page);

      await page.fill('input#name', testOrg.name);
      await page.fill('textarea#description', testOrg.description);

      await page.click('button[type="submit"]:has-text("Create")');
      await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.medium });

      // Try to create duplicate organization
      await page.click('button:has-text("Create Organization")');
      await waitForModal(page);

      await page.fill('input#name', testOrg.name);
      await page.fill('textarea#description', 'Different description');

      await page.click('button[type="submit"]:has-text("Create")');

      // Should show error message
      await expect(page.locator('.error-message, [role="alert"]')).toBeVisible({ timeout: TEST_CONFIG.timeout.short });
    });

    test('should cancel organization creation', async ({ page }) => {
      // Open modal
      await page.click('button:has-text("Create Organization")');
      await waitForModal(page);

      // Fill some data
      await page.fill('input#name', 'CancelTestOrg');
      await page.fill('textarea#description', 'This should not be created');

      // Click cancel
      await page.click('button:has-text("Cancel")');

      // Modal should close
      await expect(page.locator('.modal-overlay')).not.toBeVisible();

      // Organization should not be created
      await searchInTable(page, 'CancelTestOrg');
      const orgExists = await isTextInTable(page, 'CancelTestOrg');
      expect(orgExists).toBe(false);
    });
  });

  test.describe('Edit Organization', () => {
    let testOrg: ReturnType<typeof generateTestOrganization>;

    test.beforeEach(async ({ page }) => {
      // Create a test organization first
      testOrg = generateTestOrganization();

      await page.click('button:has-text("Create Organization")');
      await waitForModal(page);

      await page.fill('input#name', testOrg.name);
      await page.fill('textarea#description', testOrg.description);

      await page.click('button[type="submit"]:has-text("Create")');
      await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
      await waitForTableToLoad(page);

      // Search for the organization
      await searchInTable(page, testOrg.name);
    });

    test('should open edit organization modal', async ({ page }) => {
      // Click edit button for the organization
      await clickActionButtonInRow(page, testOrg.name, 'edit');

      // Modal should be visible
      await waitForModal(page);

      // Check modal title
      await expect(page.locator('.modal-title, h3, h2')).toContainText('Edit Organization');

      // Check that fields are pre-filled
      const nameInput = page.locator('input#name');
      await expect(nameInput).toHaveValue(testOrg.name);

      const descriptionInput = page.locator('textarea#description');
      await expect(descriptionInput).toHaveValue(testOrg.description);
    });

    test('should successfully edit organization details', async ({ page }) => {
      // Click edit button
      await clickActionButtonInRow(page, testOrg.name, 'edit');
      await waitForModal(page);

      // Update name
      const newName = `${testOrg.name}_Updated`;
      await page.fill('input#name', newName);

      // Update description
      const newDescription = 'Updated organization description';
      await page.fill('textarea#description', newDescription);

      // Submit form
      await page.click('button[type="submit"]:has-text("Update")');

      // Wait for modal to close
      await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.medium });

      // Wait for table to reload
      await waitForTableToLoad(page);

      // Clear search and search for updated organization
      await searchInTable(page, '');
      await searchInTable(page, newName);

      // Verify updated organization appears in table
      const orgExists = await isTextInTable(page, newName);
      expect(orgExists).toBe(true);

      // Verify updated description appears
      const descExists = await isTextInTable(page, newDescription);
      expect(descExists).toBe(true);
    });

    test('should cancel organization edit', async ({ page }) => {
      // Click edit button
      await clickActionButtonInRow(page, testOrg.name, 'edit');
      await waitForModal(page);

      // Make changes
      await page.fill('input#name', 'ShouldNotUpdate');
      await page.fill('textarea#description', 'This should not be saved');

      // Click cancel
      await page.click('button:has-text("Cancel")');

      // Modal should close
      await expect(page.locator('.modal-overlay')).not.toBeVisible();

      // Wait for table to load
      await waitForTableToLoad(page);

      // Organization should still have original name
      await searchInTable(page, testOrg.name);
      const orgExists = await isTextInTable(page, testOrg.name);
      expect(orgExists).toBe(true);

      // New name should not exist
      await searchInTable(page, 'ShouldNotUpdate');
      const newOrgExists = await isTextInTable(page, 'ShouldNotUpdate');
      expect(newOrgExists).toBe(false);
    });
  });

  test.describe('Delete Organization', () => {
    let testOrg: ReturnType<typeof generateTestOrganization>;

    test.beforeEach(async ({ page }) => {
      // Create a test organization first
      testOrg = generateTestOrganization();

      await page.click('button:has-text("Create Organization")');
      await waitForModal(page);

      await page.fill('input#name', testOrg.name);
      await page.fill('textarea#description', testOrg.description);

      await page.click('button[type="submit"]:has-text("Create")');
      await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
      await waitForTableToLoad(page);

      // Search for the organization
      await searchInTable(page, testOrg.name);
    });

    test('should successfully delete organization', async ({ page }) => {
      // Set up dialog handler to confirm deletion
      page.once('dialog', async dialog => {
        expect(dialog.type()).toBe('confirm');
        expect(dialog.message()).toContain('Are you sure');
        await dialog.accept();
      });

      // Click delete button
      await clickActionButtonInRow(page, testOrg.name, 'delete');

      // Wait for table to reload
      await waitForTableToLoad(page);

      // Clear search and verify organization is deleted
      await searchInTable(page, '');
      await searchInTable(page, testOrg.name);

      // Organization should not exist
      const orgExists = await isTextInTable(page, testOrg.name);
      expect(orgExists).toBe(false);
    });

    test('should cancel organization deletion', async ({ page }) => {
      // Set up dialog handler to dismiss deletion
      page.once('dialog', async dialog => {
        expect(dialog.type()).toBe('confirm');
        await dialog.dismiss();
      });

      // Click delete button
      await clickActionButtonInRow(page, testOrg.name, 'delete');

      // Wait a bit for any potential table reload
      await page.waitForTimeout(500);

      // Organization should still exist
      const orgExists = await isTextInTable(page, testOrg.name);
      expect(orgExists).toBe(true);
    });
  });

  test.describe('Search Organizations', () => {
    test.beforeEach(async ({ page }) => {
      // Create multiple test organizations with distinct names
      const orgs = [
        { name: `AlphaOrg_${Date.now()}`, description: 'Alpha organization for testing' },
        { name: `BetaOrg_${Date.now()}`, description: 'Beta organization for testing' },
        { name: `GammaOrg_${Date.now()}`, description: 'Gamma organization with unique description' },
      ];

      for (const org of orgs) {
        await page.click('button:has-text("Create Organization")');
        await waitForModal(page);

        await page.fill('input#name', org.name);
        await page.fill('textarea#description', org.description);

        // Wait for API response before closing modal
        const createOrgPromise = page.waitForResponse(
          response => response.url().includes('/api/v1/organizations') && response.status() === 200,
          { timeout: TEST_CONFIG.timeout.medium }
        );

        await page.click('button[type="submit"]:has-text("Create")');
        await createOrgPromise;
        await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
        await page.waitForTimeout(300);
      }

      // Clear any existing search
      await searchInTable(page, '');
      await waitForTableToLoad(page);
    });

    test('should search organizations by name', async ({ page }) => {
      // Search for "Alpha"
      await searchInTable(page, 'Alpha');

      // Should show AlphaOrg
      const alphaExists = await isTextInTable(page, 'AlphaOrg');
      expect(alphaExists).toBe(true);

      // Should not show BetaOrg
      const betaExists = await isTextInTable(page, 'BetaOrg');
      expect(betaExists).toBe(false);
    });

    test('should search organizations by description', async ({ page }) => {
      // Search by description keyword "unique"
      await searchInTable(page, 'unique');

      // Should show GammaOrg
      const gammaExists = await isTextInTable(page, 'GammaOrg');
      expect(gammaExists).toBe(true);

      // Should not show AlphaOrg
      const alphaExists = await isTextInTable(page, 'AlphaOrg');
      expect(alphaExists).toBe(false);
    });

    test('should be case-insensitive', async ({ page }) => {
      // Search with lowercase
      await searchInTable(page, 'beta');

      // Should find BetaOrg (case-insensitive)
      const betaExists = await isTextInTable(page, 'BetaOrg');
      expect(betaExists).toBe(true);
    });

    test('should clear search and show all organizations', async ({ page }) => {
      // First search for something specific
      await searchInTable(page, 'Alpha');

      // Verify filtered results
      const alphaExists = await isTextInTable(page, 'AlphaOrg');
      expect(alphaExists).toBe(true);

      // Clear search
      await searchInTable(page, '');

      // All organizations should be visible (or at least more than just Alpha)
      const tableRows = page.locator('table tbody tr, .data-table tbody tr');
      const rowCount = await tableRows.count();

      // Should have more than 1 row (not just Alpha)
      expect(rowCount).toBeGreaterThan(1);
    });

    test('should show no results for non-existent search', async ({ page }) => {
      // Search for something that doesn't exist
      await searchInTable(page, 'NonExistentOrganization12345');

      // Should show "No organizations found" message
      await expect(page.locator('td:has-text("No organizations found")')).toBeVisible();
    });
  });

  test.describe('Pagination', () => {
    test('should change page size', async ({ page }) => {
      // Get current page size
      const pageSizeSelect = page.locator('select.page-size-select');
      const currentPageSize = await pageSizeSelect.inputValue();

      // Change to different page size
      const newPageSize = currentPageSize === '10' ? '25' : '10';
      await pageSizeSelect.selectOption(newPageSize);

      // Wait for table to reload
      await waitForTableToLoad(page);

      // Verify page size changed
      const updatedPageSize = await pageSizeSelect.inputValue();
      expect(updatedPageSize).toBe(newPageSize);
    });

    test('should navigate between pages if multiple pages exist', async ({ page }) => {
      // Wait for initial load
      await waitForTableToLoad(page);

      // Set page size to 10 to standardize test
      const pageSizeSelect = page.locator('select.page-size-select');
      await pageSizeSelect.selectOption('10');
      await waitForTableToLoad(page);

      // Check if next button exists and is enabled
      const nextButton = page.locator('button:has-text("Next")');
      const isNextEnabled = await nextButton.isEnabled().catch(() => false);

      if (!isNextEnabled) {
        // Not enough data for pagination, create test organizations
        const timestamp = Date.now();
        for (let i = 0; i < 12; i++) {
          await page.click('button:has-text("Create Organization")');
          await waitForModal(page);

          await page.fill('input#name', `PaginationTest_${timestamp}_${i}`);
          await page.fill('textarea#description', `Org ${i}`);

          const createOrgPromise = page.waitForResponse(
            response => response.url().includes('/api/v1/organizations') && response.status() === 201,
            { timeout: TEST_CONFIG.timeout.medium }
          ).catch(() => null);

          await page.click('button[type="submit"]:has-text("Create")');
          await createOrgPromise;
          await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
        }

        // Reload table
        await searchInTable(page, '');
        await waitForTableToLoad(page);
      }

      // Now test pagination
      await expect(nextButton).toBeEnabled({ timeout: TEST_CONFIG.timeout.short });

      // Get current page numbers
      const currentPageButton = page.locator('.pagination-number.active');
      const currentPage = await currentPageButton.textContent();

      // Click next
      await nextButton.click();
      await waitForTableToLoad(page);

      // Verify we moved to next page
      const newPageButton = page.locator('.pagination-number.active');
      const newPage = await newPageButton.textContent();
      expect(newPage).not.toBe(currentPage);
      expect(parseInt(newPage || '0')).toBe(parseInt(currentPage || '0') + 1);

      // Go back to previous page
      const previousButton = page.locator('button:has-text("Previous")');
      await previousButton.click();
      await waitForTableToLoad(page);

      // Should be back to original page
      const finalPageButton = page.locator('.pagination-number.active');
      const finalPage = await finalPageButton.textContent();
      expect(finalPage).toBe(currentPage);
    });

    test('should disable previous button on first page', async ({ page }) => {
      // Clear search to go to first page
      await searchInTable(page, '');
      await waitForTableToLoad(page);

      // Previous button should be disabled on first page
      const previousButton = page.locator('button:has-text("Previous")');
      const isDisabled = await previousButton.isDisabled();

      expect(isDisabled).toBe(true);
    });
  });

  test.describe('Permission-Based UI', () => {
    test('should show create button for super admin', async ({ page }) => {
      // Already logged in as super admin in beforeEach
      await expect(page.locator('button:has-text("Create Organization")')).toBeVisible();
    });

    test('should show edit and delete buttons for super admin', async ({ page }) => {
      // Create a test organization
      const testOrg = generateTestOrganization();

      await page.click('button:has-text("Create Organization")');
      await waitForModal(page);
      await page.fill('input#name', testOrg.name);
      await page.fill('textarea#description', testOrg.description);
      await page.click('button[type="submit"]:has-text("Create")');
      await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
      await waitForTableToLoad(page);

      // Search for the organization
      await searchInTable(page, testOrg.name);

      // Edit and delete buttons should be visible
      const row = page.locator(`table tbody tr:has-text("${testOrg.name}"), .data-table tbody tr:has-text("${testOrg.name}")`).first();
      await expect(row.locator('button[title="Edit"]')).toBeVisible();
      await expect(row.locator('button[title="Delete"]')).toBeVisible();
    });
  });
});
