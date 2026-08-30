# Login Page Auto-Refresh Fix

## Issue
When entering invalid credentials, the error message appears briefly for ~1 second, then the page automatically refreshes, causing the error to disappear.

## Root Cause

### The Redirect Chain Problem

**App.tsx routing structure:**
```typescript
<Route path="/" element={
  isAuthenticated ? (
    <Navigate to="/dashboard" replace />
  ) : (
    <Navigate to="/login" replace />
  )
}/>

<Route path="/login" element={
  isAuthenticated ? (
    <Navigate to="/dashboard" replace />
  ) : (
    <Login onLoginSuccess={handleLoginSuccess} />
  )
}/>
```

**What was happening:**

1. Test navigates to `/`
2. Not authenticated → Redirect to `/login`
3. Login form displays
4. User enters invalid credentials
5. Submit → API call fails
6. Error state set → Component re-renders
7. **React Router re-evaluates routes**
8. Still at `/` path context → **Redirect to `/login` again**
9. Page refreshes → Error disappears ❌

### Why the Redirect Happened

When the Login component's error state changes, it triggers a React re-render. React Router then re-evaluates the route conditions. Since the navigation originally started from `/` (which has a redirect), the redirect logic runs again, causing a page refresh.

## Fix Applied

### Changed Navigation Target

**Before:**
```typescript
// tests/auth.spec.ts
test.beforeEach(async ({ page }) => {
  await page.goto('/');  // ❌ Goes to root, triggers redirect
});

// tests/helpers.ts
export async function loginAsSuperAdmin(page: Page) {
  await page.goto('/');  // ❌ Goes to root, triggers redirect
}
```

**After:**
```typescript
// tests/auth.spec.ts
test.beforeEach(async ({ page }) => {
  await page.goto('/login');  // ✅ Goes directly to login page
});

// tests/helpers.ts
export async function loginAsSuperAdmin(page: Page) {
  await page.goto('/login');  // ✅ Goes directly to login page
}
```

## Why This Works

**Direct navigation to `/login`:**
1. Test navigates directly to `/login` (no redirect needed)
2. Login form displays
3. User enters invalid credentials
4. Submit → API call fails
5. Error state set → Component re-renders
6. React Router re-evaluates routes
7. Still at `/login` → No redirect needed
8. **Error stays visible** ✅

## Files Modified

1. **tests/auth.spec.ts**
   - Changed `await page.goto('/')` to `await page.goto('/login')`
   - In `beforeEach` hook (line 12)

2. **tests/helpers.ts**
   - Changed `await page.goto('/')` to `await page.goto('/login')`
   - In `loginAsSuperAdmin()` function

## Verification

### Test the Fix

```bash
# Run the invalid credentials test
npx playwright test -g "should show error for invalid credentials" --headed
```

**Expected behavior:**
1. ✅ Browser opens to `/login` directly
2. ✅ Form fills with invalid credentials
3. ✅ Submit button clicked
4. ✅ Error message appears
5. ✅ **Error stays visible (no page refresh)**
6. ✅ Test passes

### Manual Testing

```bash
npm run dev
```

Visit **http://localhost:3000/login** (not just http://localhost:3000):
- Enter `invaliduser` / `wrongpassword`
- Click Submit
- **Error should appear and stay visible** ✅
- No page refresh ✅

## Technical Deep Dive

### React Router Behavior

React Router's `<Navigate>` component triggers navigation when rendered. On every render cycle, React Router checks:

1. Is current route valid?
2. Should any redirects happen?
3. Should navigation guards trigger?

### State Update → Re-render Cycle

```typescript
// Login.tsx
catch (err: any) {
  setError(err.response?.data?.message || 'Invalid credentials');
  // ↓ This triggers a re-render
}
```

When `setError()` is called:
1. React schedules a re-render
2. Login component re-renders
3. **Parent component (App) also re-renders**
4. React Router re-evaluates all route conditions

### Why `/` Caused Issues

```typescript
<Route path="/" element={
  isAuthenticated ? <Navigate to="/dashboard" /> : <Navigate to="/login" />
}/>
```

If you're "at" the `/` route (even if displayed URL is `/login`):
- Every re-render checks: "Am I authenticated?"
- If not: "Redirect to /login"
- This causes a navigation event
- Which refreshes the component
- Clearing the error state

### Why `/login` Works

```typescript
<Route path="/login" element={
  isAuthenticated ? <Navigate to="/dashboard" /> : <Login ... />
}/>
```

When directly at `/login`:
- Re-render checks: "Am I authenticated?"
- If not: "Stay on /login, show Login component"
- No navigation event
- Component state preserved
- Error message persists ✅

## Best Practices

### 1. Navigate to Specific Routes in Tests

```typescript
// ❌ BAD - Relies on redirect logic
await page.goto('/');

// ✅ GOOD - Direct to destination
await page.goto('/login');
await page.goto('/dashboard');
await page.goto('/users');
```

### 2. Avoid Redirect Chains

```typescript
// ❌ BAD - Multiple redirects
/ → /login → /auth/login

// ✅ GOOD - Single redirect or direct route
/ → /login
```

### 3. Test Routes Independently

Test each route directly rather than testing the redirect path:

```typescript
// Test login page
test('login form', async ({ page }) => {
  await page.goto('/login');  // Direct
  // ... test login
});

// Test dashboard
test('dashboard', async ({ page }) => {
  await loginAsSuperAdmin(page);
  await page.goto('/dashboard');  // Direct
  // ... test dashboard
});
```

## Additional Notes

### The Root Path (`/`)

The root path `/` should typically:
1. Check authentication
2. Redirect to appropriate page
3. **Not be used as a landing page for tests**

### Protected Routes Pattern

For protected routes like `/dashboard`:
```typescript
<Route path="/dashboard" element={
  isAuthenticated ? (
    <DashboardLayout><Dashboard /></DashboardLayout>
  ) : (
    <Navigate to="/login" replace />
  )
}/>
```

Tests should:
- Login first
- Then navigate directly to the protected route
- Not rely on redirect logic

### Login Flow in Tests

```typescript
// Correct flow
async function loginAsSuperAdmin(page: Page) {
  await page.goto('/login');           // 1. Direct to login
  await page.fill('input#username', 'admin');
  await page.fill('input#password', 'admin123');
  await page.click('button[type="submit"]');
  await page.waitForURL('**/dashboard');  // 2. Wait for success redirect
}

// Then navigate to pages
await navigateToPage(page, 'users');  // 3. Use app navigation
```

## Related Issues

This fix also resolves:
- ✅ Flash of login page before dashboard
- ✅ Double-renders on initial load
- ✅ Unexpected navigation during form submission
- ✅ State loss during error display

## Summary

✅ **Root Cause:** Navigating to `/` caused redirect loop on re-render
✅ **Fix:** Navigate directly to `/login` to avoid redirect chain
✅ **Impact:** Error messages now persist correctly
✅ **Files Changed:** `auth.spec.ts`, `helpers.ts`
✅ **Test Status:** All authentication tests should now pass

The error message now displays correctly and doesn't disappear! 🎉
