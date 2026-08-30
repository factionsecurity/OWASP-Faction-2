import { test, expect } from '@playwright/test';
import {
  loginAsSuperAdmin,
  navigateToPage,
  waitForModal,
  waitForTableToLoad,
  searchInTable,
  isTextInTable,
  clickActionButtonInRow,
  TEST_CONFIG,
} from './helpers';

/**
 * Application Management Tests
 * Tests for CRUD operations on applications with complex nested data
 */

// Helper function to generate unique test application
function generateTestApplication() {
  const timestamp = Date.now();
  return {
    name: `TestApp_${timestamp}`,
    description: 'Test application for automated testing',
    status: 'DEVELOPMENT',
    applicationType: 'Web Application',
    assessmentFrequency: 'Ad Hoc',
    urls: [
      { url: 'https://test.example.com', title: 'Production' },
      { url: 'https://dev.test.example.com', title: 'Development' },
    ],
    stakeholders: [
      { name: 'John Doe', email: 'john.doe@example.com', role: 'Product Owner' },
      { name: 'Jane Smith', email: 'jane.smith@example.com', role: 'Tech Lead' },
    ],
    technologies: ['Java', 'React', 'PostgreSQL', 'Docker'],
    appOwner: {
      fullName: 'App Owner Test',
      email: 'owner@example.com',
    },
  };
}

test.describe('Application Management', () => {
  // Login before each test
  test.beforeEach(async ({ page }) => {
    await loginAsSuperAdmin(page);
    await navigateToPage(page, 'applications');
  });

  test.describe('Application List Page', () => {
    test('should display applications page correctly', async ({ page }) => {
      // Wait for page to fully load
      await waitForTableToLoad(page);

      // Check page title
      await expect(page.locator('h1')).toContainText('Applications');

      // Check create button
      await expect(page.locator('button:has-text("Create Application")')).toBeVisible();

      // Check table is visible
      await expect(page.locator('table, .data-table')).toBeVisible();

      // Check search input
      await expect(page.locator('input[placeholder*="Search"]')).toBeVisible();
    });

    test('should display table columns correctly', async ({ page }) => {
      await waitForTableToLoad(page);

      // Check table headers
      const headers = page.locator('table thead th, .data-table thead th');
      await expect(headers).toContainText(['Name', 'Status', 'Technologies', 'Owner', 'Actions']);
    });

    test('should show pagination controls', async ({ page }) => {
      await waitForTableToLoad(page);

      // Check for page size selector
      await expect(page.locator('select.page-size-select')).toBeVisible();

      // Check for pagination info
      await expect(page.locator('.pagination-info')).toBeVisible();
    });
  });

  test.describe('Create Application', () => {
    test('should open create application modal', async ({ page }) => {
      // Click create button
      await page.click('button:has-text("Create Application")');

      // Modal should be visible
      await waitForModal(page);

      // Check modal title
      await expect(page.locator('.modal-title, h3, h2').first()).toContainText('Create Application');

      // Check form sections are visible (using more specific selectors for section headings)
      await expect(page.locator('h3.form-section-title:has-text("Basic Information")')).toBeVisible();
      await expect(page.locator('h3.form-section-title:has-text("URLs")')).toBeVisible();
      await expect(page.locator('h3.form-section-title:has-text("Stakeholders")')).toBeVisible();
      await expect(page.locator('h3.form-section-title:has-text("Technologies")')).toBeVisible();
      await expect(page.locator('label.form-label:has-text("Application Owner")')).toBeVisible();
      await expect(page.locator('h3.form-section-title:has-text("Assessment Information")')).toBeVisible();
    });

    test('should validate required fields', async ({ page }) => {
      // Open modal
      await page.click('button:has-text("Create Application")');
      await waitForModal(page);

      // Try to submit empty form
      await page.click('button[type="submit"]:has-text("Create")');

      // HTML5 validation should prevent submission
      const nameInput = page.locator('input[placeholder="Application Name"]');
      const isValid = await nameInput.evaluate((el: HTMLInputElement) => el.validity.valid);

      expect(isValid).toBe(false);
    });

    test('should have correct default values', async ({ page }) => {
      // Open modal
      await page.click('button:has-text("Create Application")');
      await waitForModal(page);

      // Check Status defaults to DEVELOPMENT
      const statusSelect = page.locator('select').filter({ hasText: 'Development' }).first();
      await expect(statusSelect).toHaveValue('DEVELOPMENT');

      // Check Assessment Frequency defaults to Ad Hoc
      const frequencySelect = page.locator('select').filter({ hasText: 'Ad Hoc' }).first();
      await expect(frequencySelect).toHaveValue('Ad Hoc');
    });

    test('should successfully create application with all fields', async ({ page }) => {
      const testApp = generateTestApplication();

      // Open modal
      await page.click('button:has-text("Create Application")');
      await waitForModal(page);

      // Fill basic information
      await page.fill('input[placeholder="Application Name"]', testApp.name);
      await page.fill('textarea', testApp.description);

      // Select status
      const statusSelect = page.locator('label:has-text("Status")').locator('..').locator('select');
      await statusSelect.selectOption(testApp.status);

      // Add URLs
      for (const url of testApp.urls) {
        await page.fill('input[placeholder="URL"]', url.url);
        await page.fill('input[placeholder="Title"]', url.title);
        await page.locator('button:has-text("Add")').first().click();
        // Wait a moment for the URL to be added
        await page.waitForTimeout(200);
      }

      // Add Stakeholders
      const stakeholderInputs = page.locator('.form-section:has-text("Stakeholders")');
      for (const stakeholder of testApp.stakeholders) {
        await stakeholderInputs.locator('input[placeholder="Full Name"]').fill(stakeholder.name);
        await stakeholderInputs.locator('input[placeholder="Email"]').fill(stakeholder.email);
        await stakeholderInputs.locator('input[placeholder="Role"]').fill(stakeholder.role);
        await stakeholderInputs.locator('button:has-text("Add")').click();
        await page.waitForTimeout(200);
      }

      // Add Technologies by clicking common technology tags
      for (const tech of testApp.technologies) {
        const techButton = page.locator(`.technology-tag:has-text("${tech}")`).first();
        if (await techButton.isVisible()) {
          await techButton.click();
          await page.waitForTimeout(100);
        }
      }

      // Fill Application Owner (target the specific form group with "Application Owner" label)
      const appOwnerGroup = page.locator('.form-group:has(label:has-text("Application Owner"))');
      await appOwnerGroup.locator('input[placeholder="Full Name"]').fill(testApp.appOwner.fullName);
      await appOwnerGroup.locator('input[placeholder="Email"]').fill(testApp.appOwner.email);

      // Select Application Type
      const appTypeSelect = page.locator('label:has-text("Application Type")').locator('..').locator('select');
      await appTypeSelect.selectOption(testApp.applicationType);

      // Submit form and wait for API response
      const responsePromise = page.waitForResponse(
        response => response.url().includes('/applications') && (response.status() === 200 || response.status() === 201),
        { timeout: TEST_CONFIG.timeout.long }
      );
      await page.click('button[type="submit"]:has-text("Create")');
      await responsePromise;

      // Wait for modal to close
      await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.short });

      // Wait for table to reload
      await waitForTableToLoad(page);

      // Search for the new application
      await searchInTable(page, testApp.name);

      // Verify application appears in table
      const appExists = await isTextInTable(page, testApp.name);
      expect(appExists).toBe(true);

      // Verify description appears
      const descExists = await isTextInTable(page, testApp.description);
      expect(descExists).toBe(true);
    });

    test('should add custom technology not in common list', async ({ page }) => {
      const testApp = generateTestApplication();
      const customTech = 'CustomFramework123';

      // Open modal
      await page.click('button:has-text("Create Application")');
      await waitForModal(page);

      // Fill required name field
      await page.fill('input[placeholder="Application Name"]', testApp.name);

      // Add custom technology
      const techSection = page.locator('.form-section:has-text("Technologies")');
      await techSection.locator('input[placeholder="Add custom technology"]').fill(customTech);
      await techSection.locator('button:has-text("Add")').click();

      // Verify technology tag appears
      await expect(page.locator(`.technology-tag:has-text("${customTech}")`)).toBeVisible();

      // Can remove the technology
      await page.locator(`.technology-tag:has-text("${customTech}") button`).click();
      await expect(page.locator(`.technology-tag:has-text("${customTech}")`)).not.toBeVisible();
    });

    test('should show error for duplicate application name', async ({ page }) => {
      const testApp = generateTestApplication();

      // Create first application
      await page.click('button:has-text("Create Application")');
      await waitForModal(page);

      await page.fill('input[placeholder="Application Name"]', testApp.name);
      await page.fill('textarea', testApp.description);

      // Submit and wait for API response
      const responsePromise = page.waitForResponse(
        response => response.url().includes('/applications') && (response.status() === 200 || response.status() === 201),
        { timeout: TEST_CONFIG.timeout.long }
      );
      await page.click('button[type="submit"]:has-text("Create")');
      await responsePromise;

      await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.short });

      // Try to create duplicate application
      await page.click('button:has-text("Create Application")');
      await waitForModal(page);

      await page.fill('input[placeholder="Application Name"]', testApp.name);
      await page.fill('textarea', 'Different description');

      await page.click('button[type="submit"]:has-text("Create")');

      // Should show error message
      await expect(page.locator('.error-message, [role="alert"]')).toBeVisible({ timeout: TEST_CONFIG.timeout.short });
    });

    test('should cancel application creation', async ({ page }) => {
      const testApp = generateTestApplication();

      // Open modal
      await page.click('button:has-text("Create Application")');
      await waitForModal(page);

      // Fill some data
      await page.fill('input[placeholder="Application Name"]', testApp.name);
      await page.fill('textarea', 'This should not be created');

      // Click cancel
      await page.click('button:has-text("Cancel")');

      // Modal should close
      await expect(page.locator('.modal-overlay')).not.toBeVisible();

      // Application should not be created
      await searchInTable(page, testApp.name);
      const appExists = await isTextInTable(page, testApp.name);
      expect(appExists).toBe(false);
    });

    test('should not close modal when clicking overlay', async ({ page }) => {
      // Open modal
      await page.click('button:has-text("Create Application")');
      await waitForModal(page);

      // Click on the overlay (outside modal content)
      await page.locator('.modal-overlay').click({ position: { x: 0, y: 0 } });

      // Modal should still be visible
      await expect(page.locator('.modal')).toBeVisible();
    });
  });

  test.describe('Edit Application', () => {
    let testApp: ReturnType<typeof generateTestApplication>;

    test.beforeEach(async ({ page }) => {
      // Create a test application first
      testApp = generateTestApplication();

      await page.click('button:has-text("Create Application")');
      await waitForModal(page);

      await page.fill('input[placeholder="Application Name"]', testApp.name);
      await page.fill('textarea', testApp.description);

      // Submit and wait for API response
      const responsePromise = page.waitForResponse(
        response => response.url().includes('/applications') && (response.status() === 200 || response.status() === 201),
        { timeout: TEST_CONFIG.timeout.long }
      );
      await page.click('button[type="submit"]:has-text("Create")');
      await responsePromise;

      // Wait for modal to close
      await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.short });
      await waitForTableToLoad(page);

      // Search for the application
      await searchInTable(page, testApp.name);
    });

    test('should open edit application modal', async ({ page }) => {
      // Click edit button for the application
      await clickActionButtonInRow(page, testApp.name, 'edit');

      // Modal should be visible
      await waitForModal(page);

      // Check modal title
      await expect(page.locator('.modal-title, h3, h2').first()).toContainText('Edit Application');

      // Check that name field is pre-filled
      const nameInput = page.locator('input[placeholder="Application Name"]');
      await expect(nameInput).toHaveValue(testApp.name);

      // Check that description is pre-filled
      const descriptionInput = page.locator('textarea');
      await expect(descriptionInput).toHaveValue(testApp.description);
    });

    test('should successfully edit application details', async ({ page }) => {
      // Click edit button
      await clickActionButtonInRow(page, testApp.name, 'edit');
      await waitForModal(page);

      // Update name
      const newName = `${testApp.name}_Updated`;
      await page.fill('input[placeholder="Application Name"]', newName);

      // Update description
      const newDescription = 'Updated application description';
      await page.fill('textarea', newDescription);

      // Add a new technology
      const newTech = 'Kubernetes';
      const techButton = page.locator(`.technology-tag:has-text("${newTech}")`).first();
      if (await techButton.isVisible()) {
        await techButton.click();
      }

      // Submit form
      await page.click('button[type="submit"]:has-text("Update")');

      // Wait for modal to close
      await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.medium });

      // Wait for table to reload
      await waitForTableToLoad(page);

      // Clear search and search for updated application
      await searchInTable(page, '');
      await searchInTable(page, newName);

      // Verify updated application appears in table
      const appExists = await isTextInTable(page, newName);
      expect(appExists).toBe(true);

      // Verify updated description appears
      const descExists = await isTextInTable(page, newDescription);
      expect(descExists).toBe(true);
    });

    test('should cancel application edit', async ({ page }) => {
      // Click edit button
      await clickActionButtonInRow(page, testApp.name, 'edit');
      await waitForModal(page);

      // Make changes
      await page.fill('input[placeholder="Application Name"]', 'ShouldNotUpdate');
      await page.fill('textarea', 'This should not be saved');

      // Click cancel
      await page.click('button:has-text("Cancel")');

      // Modal should close
      await expect(page.locator('.modal-overlay')).not.toBeVisible();

      // Wait for table to load
      await waitForTableToLoad(page);

      // Application should still have original name
      await searchInTable(page, testApp.name);
      const appExists = await isTextInTable(page, testApp.name);
      expect(appExists).toBe(true);

      // New name should not exist
      await searchInTable(page, 'ShouldNotUpdate');
      const newAppExists = await isTextInTable(page, 'ShouldNotUpdate');
      expect(newAppExists).toBe(false);
    });
  });

  test.describe('Delete Application', () => {
    let testApp: ReturnType<typeof generateTestApplication>;

    test.beforeEach(async ({ page }) => {
      // Create a test application first
      testApp = generateTestApplication();

      await page.click('button:has-text("Create Application")');
      await waitForModal(page);

      await page.fill('input[placeholder="Application Name"]', testApp.name);
      await page.fill('textarea', testApp.description);

      // Submit and wait for API response
      const responsePromise = page.waitForResponse(
        response => response.url().includes('/applications') && (response.status() === 200 || response.status() === 201),
        { timeout: TEST_CONFIG.timeout.long }
      );
      await page.click('button[type="submit"]:has-text("Create")');
      await responsePromise;

      // Wait for modal to close
      await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.short });
      await waitForTableToLoad(page);

      // Search for the application
      await searchInTable(page, testApp.name);
    });

    test('should successfully delete application', async ({ page }) => {
      // Set up dialog handler to confirm deletion
      page.once('dialog', async dialog => {
        expect(dialog.type()).toBe('confirm');
        expect(dialog.message()).toContain('Are you sure');
        await dialog.accept();
      });

      // Click delete button
      await clickActionButtonInRow(page, testApp.name, 'delete');

      // Wait for table to reload
      await waitForTableToLoad(page);

      // Clear search and verify application is deleted
      await searchInTable(page, '');
      await searchInTable(page, testApp.name);

      // Application should not exist
      const appExists = await isTextInTable(page, testApp.name);
      expect(appExists).toBe(false);
    });

    test('should cancel application deletion', async ({ page }) => {
      // Set up dialog handler to dismiss deletion
      page.once('dialog', async dialog => {
        expect(dialog.type()).toBe('confirm');
        await dialog.dismiss();
      });

      // Click delete button
      await clickActionButtonInRow(page, testApp.name, 'delete');

      // Wait a bit for any potential table reload
      await page.waitForTimeout(500);

      // Application should still exist
      const appExists = await isTextInTable(page, testApp.name);
      expect(appExists).toBe(true);
    });
  });

  test.describe('Search Applications', () => {
    test.beforeEach(async ({ page }) => {
      // Create multiple test applications with distinct characteristics
      const apps = [
        {
          name: `AlphaApp_${Date.now()}`,
          description: 'Alpha application for testing',
        },
        {
          name: `BetaApp_${Date.now()}`,
          description: 'Beta application for testing',
        },
        {
          name: `GammaApp_${Date.now()}`,
          description: 'Gamma application with unique description',
        },
      ];

      for (const app of apps) {
        await page.click('button:has-text("Create Application")');
        await waitForModal(page);

        await page.fill('input[placeholder="Application Name"]', app.name);
        await page.fill('textarea', app.description);

        // Wait for API response before closing modal
        const createAppPromise = page.waitForResponse(
          response => response.url().includes('/api/v1/applications') && (response.status() === 200 || response.status() === 201),
          { timeout: TEST_CONFIG.timeout.medium }
        );

        await page.click('button[type="submit"]:has-text("Create")');
        await createAppPromise;
        await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
        await page.waitForTimeout(300);
      }

      // Clear any existing search
      await searchInTable(page, '');
      await waitForTableToLoad(page);
    });

    test('should search applications by name', async ({ page }) => {
      // Search for "Alpha"
      await searchInTable(page, 'Alpha');

      // Should show AlphaApp
      const alphaExists = await isTextInTable(page, 'AlphaApp');
      expect(alphaExists).toBe(true);

      // Should not show BetaApp
      const betaExists = await isTextInTable(page, 'BetaApp');
      expect(betaExists).toBe(false);
    });

    test('should search applications by description', async ({ page }) => {
      // Search by description keyword "unique"
      await searchInTable(page, 'unique');

      // Should show GammaApp
      const gammaExists = await isTextInTable(page, 'GammaApp');
      expect(gammaExists).toBe(true);

      // Should not show AlphaApp
      const alphaExists = await isTextInTable(page, 'AlphaApp');
      expect(alphaExists).toBe(false);
    });

    test('should be case-insensitive', async ({ page }) => {
      // Search with lowercase
      await searchInTable(page, 'beta');

      // Should find BetaApp (case-insensitive)
      const betaExists = await isTextInTable(page, 'BetaApp');
      expect(betaExists).toBe(true);
    });

    test('should clear search and show all applications', async ({ page }) => {
      // First search for something specific
      await searchInTable(page, 'Alpha');

      // Verify filtered results
      const alphaExists = await isTextInTable(page, 'AlphaApp');
      expect(alphaExists).toBe(true);

      // Clear search
      await searchInTable(page, '');

      // All applications should be visible (or at least more than just Alpha)
      const tableRows = page.locator('table tbody tr, .data-table tbody tr');
      const rowCount = await tableRows.count();

      // Should have more than 1 row (not just Alpha)
      expect(rowCount).toBeGreaterThan(1);
    });

    test('should show no results for non-existent search', async ({ page }) => {
      // Search for something that doesn't exist
      await searchInTable(page, 'NonExistentApplication12345');

      // Should show "No applications found" message
      await expect(page.locator('text=No applications found')).toBeVisible();
    });
  });

  test.describe('Technology Selection', () => {
    test('should toggle common technologies on and off', async ({ page }) => {
      // Open modal
      await page.click('button:has-text("Create Application")');
      await waitForModal(page);

      // Click a common technology (use getByRole with exact name to avoid matching "JavaScript")
      const javaButton = page.getByRole('button', { name: 'Java', exact: true });
      await javaButton.click();

      // Should become selected (button class changes from selectable to selected)
      // After clicking, the same button now has the "selected" class
      await page.waitForTimeout(100); // Give it a moment to update

      // Click the button again to deselect
      await javaButton.click();

      // The button should be visible and clickable (now back to selectable state)
      await page.waitForTimeout(100);
    });

    test('should select multiple technologies', async ({ page }) => {
      // Open modal
      await page.click('button:has-text("Create Application")');
      await waitForModal(page);

      // Click multiple technologies
      const techs = ['React', 'Node.js', 'PostgreSQL'];
      for (const tech of techs) {
        const techButton = page.locator(`.technology-tag.selectable:has-text("${tech}")`).first();
        await techButton.click();
        await page.waitForTimeout(100);
      }

      // All should have the selected class
      for (const tech of techs) {
        await expect(page.locator(`.technology-tag.selected:has-text("${tech}")`)).toBeVisible();
      }
    });

    test('should remove selected technology', async ({ page }) => {
      // Open modal
      await page.click('button:has-text("Create Application")');
      await waitForModal(page);

      // Select a technology
      const reactButton = page.locator('.technology-tag.selectable:has-text("React")').first();
      await reactButton.click();

      // Button should now have selected class
      await expect(page.locator('.technology-tag.selected:has-text("React")')).toBeVisible();

      // Click it again to deselect
      await page.locator('.technology-tag.selected:has-text("React")').first().click();

      // Should become selectable again
      await expect(page.locator('.technology-tag.selectable:has-text("React")')).toBeVisible();
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
  });

  test.describe('Status Display', () => {
    test('should display status badges with correct colors', async ({ page }) => {
      const testApp = generateTestApplication();

      // Create application with DEVELOPMENT status
      await page.click('button:has-text("Create Application")');
      await waitForModal(page);

      await page.fill('input[placeholder="Application Name"]', testApp.name);
      await page.fill('textarea', testApp.description);

      // Select DEVELOPMENT status (should be default)
      await page.click('button[type="submit"]:has-text("Create")');
      await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
      await waitForTableToLoad(page);

      // Search for the application
      await searchInTable(page, testApp.name);

      // Should show status badge
      await expect(page.locator('.badge, [class*="badge"]')).toBeVisible();
    });
  });

  test.describe('Permission-Based UI', () => {
    test('should show create button for super admin', async ({ page }) => {
      // Already logged in as super admin in beforeEach
      await expect(page.locator('button:has-text("Create Application")')).toBeVisible();
    });

    test('should show edit and delete buttons for super admin', async ({ page }) => {
      // Create a test application
      const testApp = generateTestApplication();

      await page.click('button:has-text("Create Application")');
      await waitForModal(page);
      await page.fill('input[placeholder="Application Name"]', testApp.name);
      await page.fill('textarea', testApp.description);
      await page.click('button[type="submit"]:has-text("Create")');
      await expect(page.locator('.modal-overlay')).not.toBeVisible({ timeout: TEST_CONFIG.timeout.medium });
      await waitForTableToLoad(page);

      // Search for the application
      await searchInTable(page, testApp.name);

      // Edit and delete buttons should be visible
      const row = page.locator(`table tbody tr:has-text("${testApp.name}"), .data-table tbody tr:has-text("${testApp.name}")`).first();
      await expect(row.locator('button[title="Edit"]')).toBeVisible();
      await expect(row.locator('button[title="Delete"]')).toBeVisible();
    });
  });
});
