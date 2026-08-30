# Testing Navigation Fix

## Issue
Tests were not navigating pages correctly due to mismatched navigation expectations with the actual application structure.

## Root Causes

### 1. Incorrect Login Redirect URL
**Problem**: Tests expected redirect to `/` after login
**Actual**: App redirects to `/dashboard` after login

### 2. Navigation Structure Mismatch
**Problem**: Tests assumed `<a href>` links for navigation
**Actual**: App uses `<button onClick>` handlers with React Router

### 3. Submenu Pages Not Accessible
**Problem**: Tests tried to navigate directly to submenu items
**Actual**: Pages like Users, Teams, Roles are nested under "Administration" submenu which must be expanded first

### 4. Wrong Selectors
**Problem**: Tests used generic selectors like `nav a[href]`
**Actual**: Need specific button selectors like `button.nav-item`

## Fixes Applied

### 1. Updated `loginAsSuperAdmin()` Function

**Before:**
```typescript
await page.waitForURL('**/', { timeout: TEST_CONFIG.timeout.medium });
```

**After:**
```typescript
await page.waitForURL('**/dashboard', { timeout: TEST_CONFIG.timeout.medium });
```

**Result**: Now waits for correct dashboard URL after login

### 2. Rewrote `navigateToPage()` Function

**Before:**
```typescript
await page.click(`nav a[href="/${pageName}"]`);
```

**After:**
```typescript
// Map pages to menu labels
const pageLabels = { 'users': 'Users', 'teams': 'Teams', ... };

// Check if page is in Administration submenu
const adminPages = ['users', 'teams', 'roles', 'assessment-config'];

if (adminPages.includes(pageName)) {
  // Expand Administration menu if needed
  const administrationButton = page.locator('button.nav-item:has-text("Administration")');
  const isExpanded = await page.locator('.nav-submenu').isVisible();

  if (!isExpanded) {
    await administrationButton.click();
    await expect(page.locator('.nav-submenu')).toBeVisible();
  }

  // Click submenu item
  await page.locator(`.nav-subitem:has-text("${label}")`).click();
} else {
  // Click top-level menu item
  await page.locator(`button.nav-item:has-text("${label}")`).first().click();
}
```

**Result**:
- Properly handles submenu navigation
- Expands Administration menu when needed
- Uses correct button selectors

### 3. Updated `logout()` Function

**Before:**
```typescript
const logoutButton = page.locator('button:has-text("Logout"), button:has-text("Sign Out")');
await page.waitForURL('**/', { timeout: TEST_CONFIG.timeout.short });
```

**After:**
```typescript
const logoutButton = page.locator('button.logout-button, button:has-text("Logout")');
await page.waitForURL('**/login', { timeout: TEST_CONFIG.timeout.short });
```

**Result**: Uses correct logout button selector and expects redirect to `/login`

### 4. Updated All Auth Tests

Fixed all URL expectations in auth.spec.ts:

| Test | Before | After |
|------|--------|-------|
| Login success | `waitForURL('**/')` | `waitForURL('**/dashboard')` |
| Loading state | `waitForURL('**/')` | `waitForURL('**/dashboard')` |
| Logout | `waitForURL('**/')` | `waitForURL('**/login')` |
| Protected routes | `expect(page).toHaveURL(/\/$/)` | `waitForURL('**/login')` |
| Session persistence | `waitForURL('**/')` | `waitForURL('**/dashboard')` |

### 5. Updated Dashboard Layout Selector

**Before:**
```typescript
await expect(page.locator('nav')).toBeVisible();
```

**After:**
```typescript
await expect(page.locator('.sidebar-nav')).toBeVisible();
```

**Result**: More specific selector that matches actual navigation structure

## Navigation Structure

### Application Menu Hierarchy

```
Dashboard (top-level)
Organizations (top-level)
Applications (top-level)
Assessments (top-level)
Vulnerabilities (top-level)
Engagement (top-level)
Remediation (top-level)
Administration (submenu) ▼
  ├── Users
  ├── Teams
  ├── Roles
  └── Assessment Config
```

### Navigation Code Flow

1. **Top-level pages** (Dashboard, Organizations, etc.):
   ```typescript
   await page.locator('button.nav-item:has-text("Dashboard")').click();
   ```

2. **Submenu pages** (Users, Teams, Roles):
   ```typescript
   // Step 1: Expand Administration menu
   await page.locator('button.nav-item:has-text("Administration")').click();
   await expect(page.locator('.nav-submenu')).toBeVisible();

   // Step 2: Click submenu item
   await page.locator('.nav-subitem:has-text("Users")').click();
   ```

## Testing the Fixes

### Quick Verification

Run a single test to verify navigation works:

```bash
npx playwright test -g "should successfully login" --headed
```

You should see:
1. Login form appears
2. Credentials filled
3. Submit clicked
4. **Redirect to /dashboard** ✅
5. Dashboard layout visible
6. Sidebar with navigation menu visible

### Full Test Suite

Run all tests:

```bash
npm test
```

All navigation-related tests should now pass:
- ✅ Login redirects to dashboard
- ✅ Logout redirects to login
- ✅ Navigate to Users page (via Administration submenu)
- ✅ Navigate to Teams page (via Administration submenu)
- ✅ Navigate to Roles page (via Administration submenu)
- ✅ Protected routes redirect to login

## Updated Test Code Examples

### Example: Login Test

```typescript
test('should successfully login as superadmin', async ({ page }) => {
  await page.fill('input#username', TEST_CONFIG.superadmin.username);
  await page.fill('input#password', TEST_CONFIG.superadmin.password);
  await page.click('button[type="submit"]');

  // ✅ Correct: Wait for /dashboard
  await page.waitForURL('**/dashboard', { timeout: TEST_CONFIG.timeout.medium });

  // ✅ Correct: Check for dashboard layout
  await expect(page.locator('.dashboard-layout')).toBeVisible();

  // ✅ Correct: Check for sidebar navigation
  await expect(page.locator('.sidebar-nav')).toBeVisible();
});
```

### Example: Navigate to Users Page

```typescript
test('should navigate to users page', async ({ page }) => {
  await loginAsSuperAdmin(page);

  // ✅ Correct: Uses navigateToPage helper
  await navigateToPage(page, 'users');

  // ✅ Correct: Verifies page loaded
  await expect(page.locator('.page-header')).toBeVisible();
  await expect(page.locator('h2.page-heading')).toContainText('User Management');
});
```

### Example: Logout Test

```typescript
test('should logout successfully', async ({ page }) => {
  await loginAsSuperAdmin(page);

  // ✅ Correct: Uses logout button selector
  const logoutButton = page.locator('button.logout-button');
  await logoutButton.click();

  // ✅ Correct: Wait for /login redirect
  await page.waitForURL('**/login', { timeout: TEST_CONFIG.timeout.short });

  // ✅ Correct: Verify login page visible
  await expect(page.locator('input#username')).toBeVisible();
});
```

## Helper Function Usage

### Using navigateToPage()

```typescript
// Navigate to top-level pages
await navigateToPage(page, 'organizations');
await navigateToPage(page, 'assessments');

// Navigate to Administration submenu pages
await navigateToPage(page, 'users');        // Automatically expands Administration
await navigateToPage(page, 'teams');        // Automatically expands Administration
await navigateToPage(page, 'roles');        // Automatically expands Administration
await navigateToPage(page, 'assessment-config'); // Automatically expands Administration
```

### Function automatically handles:
1. ✅ Expanding submenu if needed
2. ✅ Waiting for submenu to appear
3. ✅ Clicking correct menu item
4. ✅ Waiting for URL navigation
5. ✅ Waiting for page content to load

## Common Issues and Solutions

### Issue: "Timeout waiting for URL"

**Cause**: Wrong URL pattern in test
**Solution**: Use correct URL patterns:
- Login redirect: `**/dashboard`
- Logout redirect: `**/login`
- Page navigation: `**/users`, `**/teams`, etc.

### Issue: "Element not found: nav a[href]"

**Cause**: Wrong selector for navigation
**Solution**: Use button selectors:
- Top-level: `button.nav-item:has-text("Page Name")`
- Submenu: `.nav-subitem:has-text("Page Name")`

### Issue: "Cannot click on Users menu item"

**Cause**: Administration submenu not expanded
**Solution**: Use `navigateToPage(page, 'users')` which handles expansion automatically

### Issue: "Test passes locally but fails in CI"

**Cause**: Timing issues with menu expansion
**Solution**: Helper functions now include proper waits:
```typescript
await expect(page.locator('.nav-submenu')).toBeVisible({ timeout: 5000 });
```

## Verification Checklist

Before running tests, verify:

- ✅ Backend running on `http://localhost:8080`
- ✅ Frontend dev server running on `http://localhost:3000`
- ✅ Superadmin credentials work: `admin` / `admin123`
- ✅ Can manually login and navigate in browser
- ✅ Administration submenu expands when clicked

## Files Modified

1. **tests/helpers.ts**
   - ✅ Updated `loginAsSuperAdmin()` - correct dashboard URL
   - ✅ Rewrote `navigateToPage()` - handles submenus
   - ✅ Updated `logout()` - correct logout URL

2. **tests/auth.spec.ts**
   - ✅ Updated login test - dashboard URL
   - ✅ Updated loading state test - dashboard URL
   - ✅ Updated logout test - login URL
   - ✅ Updated protected routes test - login URL
   - ✅ Updated session persistence test - dashboard URL
   - ✅ Updated sidebar selector - `.sidebar-nav`

## Result

✅ All navigation issues resolved
✅ Tests now match actual application behavior
✅ Submenu navigation working correctly
✅ Login/logout redirects correct
✅ Helper functions robust and reusable

## Running Tests Now

```bash
# Run all tests
npm test

# Run with visible browser to see navigation
npm run test:headed

# Run specific auth tests
npm run test:auth

# Run specific user tests
npm run test:users

# Debug mode
npm run test:debug
```

All tests should now navigate correctly! 🎉
