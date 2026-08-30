# UI Testing Framework - Playwright

## Overview

This project uses **Playwright** for end-to-end (E2E) UI testing. Playwright is a modern, fast, and reliable testing framework that runs tests in real browsers (Chromium, Firefox, WebKit).

## Features

- ✅ **Multi-browser support** - Test in Chrome, Firefox, and Safari
- ✅ **Fast execution** - Parallel test execution
- ✅ **Auto-wait** - Waits for elements automatically
- ✅ **Screenshots & Videos** - Captures failures automatically
- ✅ **TypeScript** - Full type safety
- ✅ **CI/CD Ready** - Works in GitHub Actions, GitLab CI, etc.

## Test Coverage

### Authentication Tests (`tests/auth.spec.ts`)
- ✅ Login form display
- ✅ Validation for empty credentials
- ✅ Invalid credentials error handling
- ✅ Successful superadmin login
- ✅ Loading states during login
- ✅ Logout functionality
- ✅ Session persistence across page refreshes
- ✅ Protected route access control

### User Management Tests (`tests/users.spec.ts`)
- ✅ User list page display
- ✅ Create new user
- ✅ Edit existing user
- ✅ Delete user
- ✅ Search users (by username, email, first name, last name)
- ✅ Validation and error handling
- ✅ Table interactions (sorting, pagination)

## Installation

Playwright is already installed. If you need to reinstall:

```bash
npm install -D @playwright/test @types/node
```

Install browser binaries:

```bash
npx playwright install
```

## Running Tests

### Run all tests (headless)
```bash
npm test
```

### Run tests with browser visible
```bash
npm run test:headed
```

### Run tests in interactive UI mode
```bash
npm run test:ui
```

### Run tests in debug mode
```bash
npm run test:debug
```

### Run specific test suite
```bash
npm run test:auth      # Authentication tests only
npm run test:users     # User management tests only
```

### Run tests in specific browser
```bash
npm run test:chromium  # Chrome/Edge
npm run test:firefox   # Firefox
npm run test:webkit    # Safari
```

### View test report
```bash
npm run test:report
```

## Test Configuration

Tests are configured in `playwright.config.ts`:

```typescript
{
  testDir: './tests',           // Test files location
  baseURL: 'http://localhost:3000',  // Frontend URL
  timeout: 30000,               // Test timeout
  retries: 2,                   // Retry failed tests (CI only)
  workers: 4,                   // Parallel workers
  reporter: ['html', 'list'],   // Test reports
}
```

### Environment Variables

You can customize test behavior with environment variables:

```bash
# Frontend URL
BASE_URL=http://localhost:3000

# Backend API URL
API_URL=http://localhost:8080/api/v1

# Test credentials
TEST_SUPERADMIN_USERNAME=admin
TEST_SUPERADMIN_PASSWORD=admin123
```

Example:
```bash
BASE_URL=http://localhost:5173 npm test
```

## Test Structure

### Test Files

```
tests/
├── helpers.ts          # Shared utility functions
├── auth.spec.ts        # Authentication tests
└── users.spec.ts       # User management tests
```

### Helper Functions

The `helpers.ts` file provides reusable functions:

```typescript
// Authentication
loginAsSuperAdmin(page)           // Login as admin
logout(page)                      // Logout

// Navigation
navigateToPage(page, 'users')     // Navigate to page

// Modal interactions
waitForModal(page)                // Wait for modal to open
closeModal(page)                  // Close modal

// Table interactions
waitForTableToLoad(page)          // Wait for table data
searchInTable(page, 'search term') // Search in table
clickActionButtonInRow(page, 'identifier', 'edit') // Click row action

// Data generation
generateTestUser()                // Create test user data
generateRandomString(8)           // Random string
```

## Writing New Tests

### Basic Test Structure

```typescript
import { test, expect } from '@playwright/test';
import { loginAsSuperAdmin, navigateToPage } from './helpers';

test.describe('Feature Name', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsSuperAdmin(page);
    await navigateToPage(page, 'users');
  });

  test('should do something', async ({ page }) => {
    // Arrange
    await page.click('button:has-text("Create")');

    // Act
    await page.fill('input[name="username"]', 'testuser');
    await page.click('button[type="submit"]');

    // Assert
    await expect(page.locator('text="Success"')).toBeVisible();
  });
});
```

### Locator Strategies

Playwright supports multiple ways to find elements:

```typescript
// By text
page.locator('text="Login"')
page.locator('button:has-text("Submit")')

// By role
page.locator('role=button[name="Submit"]')

// By CSS selector
page.locator('button.submit-btn')
page.locator('#username')

// By test ID
page.locator('[data-testid="login-button"]')

// By label
page.locator('label:has-text("Username") ~ input')

// Combined
page.locator('form').locator('button[type="submit"]')
```

### Assertions

```typescript
// Visibility
await expect(page.locator('.error')).toBeVisible()
await expect(page.locator('.modal')).not.toBeVisible()

// Text content
await expect(page.locator('h1')).toContainText('Dashboard')
await expect(page.locator('input')).toHaveValue('admin')

// Count
await expect(page.locator('table tbody tr')).toHaveCount(10)

// URL
await expect(page).toHaveURL('/users')

// Attributes
await expect(page.locator('button')).toBeEnabled()
await expect(page.locator('button')).toBeDisabled()
```

### Waiting Strategies

```typescript
// Wait for element
await page.waitForSelector('.table')

// Wait for navigation
await page.waitForURL('**/users')

// Wait for network request
await page.waitForResponse(resp =>
  resp.url().includes('/api/users') && resp.status() === 200
)

// Wait for function
await page.waitForFunction(() =>
  document.querySelectorAll('table tr').length > 0
)

// Manual wait (avoid when possible)
await page.waitForTimeout(1000)
```

## Best Practices

### 1. Use Test Data Generators

```typescript
// Good: Generate unique test data
const user = generateTestUser();
await page.fill('input#username', user.username);

// Bad: Hardcoded data (causes flaky tests)
await page.fill('input#username', 'testuser');
```

### 2. Clean Up Test Data

```typescript
test('should create user', async ({ page }) => {
  const user = generateTestUser();

  // Create user
  await createUser(page, user);

  // Test something

  // Clean up
  await deleteUser(page, user.username);
});
```

### 3. Use beforeEach for Setup

```typescript
test.describe('Users', () => {
  test.beforeEach(async ({ page }) => {
    // Common setup for all tests
    await loginAsSuperAdmin(page);
    await navigateToPage(page, 'users');
  });

  test('test 1', async ({ page }) => { /* ... */ });
  test('test 2', async ({ page }) => { /* ... */ });
});
```

### 4. Handle Async Operations

```typescript
// Wait for modal to close
await expect(page.locator('.modal')).not.toBeVisible({
  timeout: 10000
});

// Wait for table to update
await waitForTableToLoad(page);
```

### 5. Use Descriptive Test Names

```typescript
// Good
test('should show error message when submitting empty form')

// Bad
test('test 1')
```

## Debugging Tests

### Debug Mode

Run tests with `--debug` flag to step through tests:

```bash
npm run test:debug
```

This opens Playwright Inspector where you can:
- Step through test line by line
- Inspect elements
- View console logs
- See network requests

### Screenshots

Playwright automatically captures screenshots on failure. Find them in:
```
test-results/
└── auth-login-chromium/
    └── test-failed-1.png
```

### Videos

Videos are captured for failed tests:
```
test-results/
└── auth-login-chromium/
    └── video.webm
```

### Traces

View detailed traces in Playwright Trace Viewer:

```bash
npm run test:report
```

Then click on a failed test to see:
- DOM snapshots at each step
- Network activity
- Console logs
- Screenshots

## CI/CD Integration

### GitHub Actions

```yaml
name: E2E Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Install Node.js
        uses: actions/setup-node@v3
        with:
          node-version: 18

      - name: Install dependencies
        run: npm ci

      - name: Install Playwright browsers
        run: npx playwright install --with-deps

      - name: Run tests
        run: npm test
        env:
          BASE_URL: http://localhost:3000
          TEST_SUPERADMIN_USERNAME: ${{ secrets.TEST_ADMIN_USER }}
          TEST_SUPERADMIN_PASSWORD: ${{ secrets.TEST_ADMIN_PASS }}

      - name: Upload test report
        if: always()
        uses: actions/upload-artifact@v3
        with:
          name: playwright-report
          path: playwright-report/
```

## Troubleshooting

### Tests are flaky

**Solution**: Use proper waits instead of `waitForTimeout`:
```typescript
// Bad
await page.waitForTimeout(1000);

// Good
await expect(page.locator('.table')).toBeVisible();
await waitForTableToLoad(page);
```

### Element not found

**Solution**: Add explicit waits:
```typescript
await expect(page.locator('button')).toBeVisible({ timeout: 10000 });
```

### Tests fail in CI but pass locally

**Solution**:
1. Check environment variables
2. Increase timeouts for slower CI machines
3. Use `--retries=2` in CI

### Browser installation issues

**Solution**:
```bash
# Reinstall browsers
npx playwright install --force

# Install system dependencies
npx playwright install-deps
```

## Test Maintenance

### Running Tests Regularly

Run tests:
- Before committing code
- In pull request CI checks
- On scheduled basis (nightly builds)

### Updating Tests

When UI changes:
1. Update locators if needed
2. Update expected text/values
3. Update helper functions
4. Re-run tests to verify

### Code Coverage

For component-level testing with coverage, consider adding:
- Vitest + React Testing Library (unit tests)
- Istanbul/NYC (coverage reporting)

## Performance Tips

### Run tests in parallel

```bash
# Use more workers for faster execution
npm test -- --workers=8
```

### Run specific tests during development

```bash
# Run single test file
npx playwright test tests/users.spec.ts

# Run tests matching pattern
npx playwright test -g "should create user"

# Run only changed tests
npx playwright test --only-changed
```

### Use tags for test organization

```typescript
test('should login @smoke', async ({ page }) => {
  // Critical smoke test
});

test('should export data @slow', async ({ page }) => {
  // Slow test
});
```

```bash
# Run only smoke tests
npx playwright test --grep @smoke

# Skip slow tests
npx playwright test --grep-invert @slow
```

## Next Steps

### Recommended Test Additions

1. **Team Management Tests**
   - Create/edit/delete teams
   - Add/remove users from teams

2. **Role Management Tests**
   - Create/edit/delete roles
   - Assign permissions

3. **Assessment Tests**
   - Create/edit assessments
   - Add vulnerabilities

4. **Integration Tests**
   - API mock responses
   - Error scenarios
   - Network failures

5. **Visual Regression Tests**
   - Screenshot comparisons
   - Component visual testing

### Additional Tools

Consider adding:
- **Allure Report** - Better test reporting
- **Playwright Test Generator** - Record tests
- **Lighthouse CI** - Performance testing
- **Axe** - Accessibility testing

## Resources

- [Playwright Documentation](https://playwright.dev)
- [Playwright Best Practices](https://playwright.dev/docs/best-practices)
- [Playwright API Reference](https://playwright.dev/docs/api/class-playwright)
- [Example Tests](https://github.com/microsoft/playwright/tree/main/tests)

## Support

For issues or questions:
1. Check Playwright documentation
2. Review existing test examples
3. Check test output and traces
4. Open an issue in the project repository
