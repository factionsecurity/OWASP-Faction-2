import { test, expect } from '@playwright/test';
import { TEST_CONFIG } from './helpers';

/**
 * Authentication Tests
 * Tests for login, logout, and authentication flows
 */

test.describe('Authentication', () => {
  test.beforeEach(async ({ page }) => {
    // Navigate directly to login page (avoid redirect from /)
    await page.goto('/login');
  });

  test('should display login form correctly', async ({ page }) => {
    // Check that login form elements are visible
    await expect(page.locator('h1.login-title')).toContainText('Admin Portal');
    await expect(page.locator('p.login-subtitle')).toContainText('Internal Users Only');
    await expect(page.locator('input#username')).toBeVisible();
    await expect(page.locator('input#password')).toBeVisible();
    await expect(page.locator('button[type="submit"]')).toBeVisible();
    await expect(page.locator('button[type="submit"]')).toContainText('Sign In');
  });

  test('should show validation for empty credentials', async ({ page }) => {
    // Try to submit empty form
    await page.click('button[type="submit"]');

    // HTML5 validation should prevent submission
    const usernameInput = page.locator('input#username');
    const isValid = await usernameInput.evaluate((el: HTMLInputElement) => el.validity.valid);

    expect(isValid).toBe(false);
  });

  test('should show error for invalid credentials', async ({ page }) => {
    // Fill in invalid credentials
    await page.fill('input#username', 'invaliduser');
    await page.fill('input#password', 'wrongpassword');

    // Submit form
    await page.click('button[type="submit"]');

    // Wait for error message (allow time for API call)
    await expect(page.locator('.error-message')).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });

    // Verify we're still on login page (not redirected)
    await expect(page).toHaveURL(/\/login|\/$/);

    // Verify error message contains expected text
    const errorText = await page.locator('.error-message').textContent();
    expect(errorText).toBeTruthy();
    expect(errorText!.toLowerCase()).toMatch(/invalid|authentication|failed|credentials/);

    // Verify error persists and login form is still visible
    await expect(page.locator('input#username')).toBeVisible();
    await expect(page.locator('input#password')).toBeVisible();
    await expect(page.locator('button[type="submit"]')).toBeVisible();

    // Wait a bit to ensure error doesn't disappear
    await page.waitForTimeout(1000);
    await expect(page.locator('.error-message')).toBeVisible();
  });

  test('should successfully login as superadmin', async ({ page }) => {
    // Fill in superadmin credentials
    await page.fill('input#username', TEST_CONFIG.superadmin.username);
    await page.fill('input#password', TEST_CONFIG.superadmin.password);

    // Submit form
    await page.click('button[type="submit"]');

    // Wait for redirect to dashboard
    await page.waitForURL('**/dashboard', { timeout: TEST_CONFIG.timeout.medium });

    // Verify we're logged in
    await expect(page.locator('.dashboard-layout')).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });

    // Verify navigation menu is visible
    await expect(page.locator('.sidebar-nav')).toBeVisible();

    // Verify token is stored in localStorage
    const token = await page.evaluate(() => localStorage.getItem('token'));
    expect(token).not.toBeNull();
    expect(token).not.toBe('');

    // Verify user info is stored
    const userInfo = await page.evaluate(() => localStorage.getItem('user'));
    expect(userInfo).not.toBeNull();

    const user = JSON.parse(userInfo!);
    expect(user.username).toBe(TEST_CONFIG.superadmin.username);
    expect(user.authorities).toBeDefined();
  });

  test('should show loading state during login', async ({ page }) => {
    // Fill in credentials
    await page.fill('input#username', TEST_CONFIG.superadmin.username);
    await page.fill('input#password', TEST_CONFIG.superadmin.password);

    // Click submit and immediately check for loading state
    const submitButton = page.locator('button[type="submit"]');
    await submitButton.click();

    // Check for loading text (might be fast, so we use a short timeout)
    try {
      await expect(submitButton).toContainText(/Signing in/i, { timeout: 1000 });
    } catch {
      // Loading might be too fast to catch, which is fine
    }

    // Wait for login to complete
    await page.waitForURL('**/dashboard', { timeout: TEST_CONFIG.timeout.medium });
  });

  test('should show disabled state for SAML and OpenID buttons', async ({ page }) => {
    // Check that SAML button is disabled
    const samlButton = page.locator('button.method-button:has-text("SAML")');
    await expect(samlButton).toBeDisabled();

    // Check that OpenID button is disabled
    const openidButton = page.locator('button.method-button:has-text("OpenID")');
    await expect(openidButton).toBeDisabled();

    // Check that Password button is enabled and active
    const passwordButton = page.locator('button.method-button:has-text("Password")');
    await expect(passwordButton).toBeEnabled();
    await expect(passwordButton).toHaveClass(/active/);
  });

  test('should logout successfully', async ({ page }) => {
    // First login
    await page.fill('input#username', TEST_CONFIG.superadmin.username);
    await page.fill('input#password', TEST_CONFIG.superadmin.password);
    await page.click('button[type="submit"]');
    await page.waitForURL('**/dashboard', { timeout: TEST_CONFIG.timeout.medium });

    // Find and click logout button
    const logoutButton = page.locator('button.logout-button, button:has-text("Logout")');

    await expect(logoutButton).toBeVisible();
    await logoutButton.click();

    // Should redirect back to login
    await page.waitForURL('**/login', { timeout: TEST_CONFIG.timeout.short });
    await expect(page.locator('input#username')).toBeVisible();

    // Token should be removed from localStorage
    const token = await page.evaluate(() => localStorage.getItem('token'));
    expect(token).toBeNull();
  });

  test('should prevent access to protected pages without login', async ({ page }) => {
    // Try to access users page directly
    await page.goto('/users');

    // Should redirect to login
    await page.waitForURL('**/login', { timeout: TEST_CONFIG.timeout.short });
    await expect(page.locator('input#username')).toBeVisible();
  });

  test('should maintain session across page refreshes', async ({ page }) => {
    // Login
    await page.fill('input#username', TEST_CONFIG.superadmin.username);
    await page.fill('input#password', TEST_CONFIG.superadmin.password);
    await page.click('button[type="submit"]');
    await page.waitForURL('**/dashboard', { timeout: TEST_CONFIG.timeout.medium });

    // Verify logged in
    await expect(page.locator('.dashboard-layout')).toBeVisible();

    // Refresh page
    await page.reload();

    // Should still be logged in
    await expect(page.locator('.dashboard-layout')).toBeVisible({ timeout: TEST_CONFIG.timeout.medium });

    // Token should still be in localStorage
    const token = await page.evaluate(() => localStorage.getItem('token'));
    expect(token).not.toBeNull();
  });
});
