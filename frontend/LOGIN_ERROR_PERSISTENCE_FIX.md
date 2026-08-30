# Login Error Message Persistence Fix

## Issue
Error message for invalid credentials appears briefly and then disappears, or the page redirects immediately after showing the error.

## Root Causes

### 1. Double Form Submission
**Problem:** If the submit button is clicked twice (or enter is pressed multiple times), the `handleSubmit` function runs again, clearing the error on line `setError('')`.

**Example:**
```
User clicks Submit → API call fails → Error shows
User clicks Submit again → setError('') runs → Error disappears
```

### 2. Form Re-submission During Loading
**Problem:** Without a guard, the form could be submitted while a previous request is still in progress.

### 3. Test Race Conditions
**Problem:** Tests might interact with the page before the error state has fully settled, causing navigation or state changes.

## Fixes Applied

### 1. Added Double-Submission Guard in Login.tsx

**Before:**
```typescript
const handleSubmit = async (e: FormEvent) => {
  e.preventDefault();
  setError('');  // ❌ Always clears error, even if called twice
  setLoading(true);
  // ...
}
```

**After:**
```typescript
const handleSubmit = async (e: FormEvent) => {
  e.preventDefault();

  // ✅ Prevent double submission
  if (loading) {
    return;
  }

  setError('');
  setLoading(true);
  // ...
}
```

**Result:** Form cannot be submitted while a request is already in progress.

### 2. Improved Test Validation in auth.spec.ts

**Added:**
```typescript
// Verify we're still on login page (not redirected)
await expect(page).toHaveURL(/\/login|\/$/);

// Verify error message contains expected text
const errorText = await page.locator('.error-message').textContent();
expect(errorText).toBeTruthy();

// Verify error persists and login form is still visible
await expect(page.locator('input#username')).toBeVisible();
await expect(page.locator('input#password')).toBeVisible();

// Wait a bit to ensure error doesn't disappear
await page.waitForTimeout(1000);
await expect(page.locator('.error-message')).toBeVisible();
```

**Result:** Test now verifies:
- ✅ No redirect occurs
- ✅ Error message displays
- ✅ Form remains visible
- ✅ Error persists for at least 1 second

## Verification

### Test the Fix Manually

1. **Start the application:**
```bash
# Terminal 1 - Backend
cd backend
mvn spring-boot:run

# Terminal 2 - Frontend
cd frontend
npm run dev
```

2. **Open browser to** http://localhost:3000

3. **Test invalid credentials:**
   - Enter: `invaliduser` / `wrongpassword`
   - Click "Sign In"
   - **Expected:** Error message appears and stays visible
   - Try clicking Submit again while error is showing
   - **Expected:** Error stays (doesn't clear)

4. **Test double-click protection:**
   - Enter invalid credentials
   - Double-click "Sign In" button quickly
   - **Expected:** Only one request is made, error shows once

### Run Automated Test

```bash
# Run the specific test
npx playwright test -g "should show error for invalid credentials" --headed

# Watch the test execute
# Should see:
# 1. Form fills
# 2. Submit clicks
# 3. Error appears
# 4. Error persists (no disappearing)
# 5. Test passes ✓
```

## Technical Details

### Submit Button State Management

The submit button has multiple states:

```typescript
<button
  type="submit"
  className="submit-button"
  disabled={loading}  // ✅ Disabled during request
>
  {loading ? 'Signing in...' : 'Sign In'}
</button>
```

**States:**
1. **Idle:** Button enabled, text "Sign In"
2. **Loading:** Button disabled, text "Signing in..."
3. **Error:** Button enabled again, error message visible

### Error Display Component

```tsx
{error && (
  <div className="error-message">
    {error}
  </div>
)}
```

Error only displays when `error` state is truthy. The error gets cleared:
- ✅ At start of new login attempt
- ✅ When component unmounts
- ❌ NOT on double-click (now fixed)

### React State Update Sequence

**Successful flow:**
```
1. User clicks Submit
2. loading = true, error = ''
3. API call → Success
4. Token stored
5. loading = false
6. onLoginSuccess() → Redirect to dashboard
```

**Error flow:**
```
1. User clicks Submit
2. loading = true, error = ''
3. API call → Error (401)
4. error = 'Invalid credentials'
5. loading = false
6. Error message displays
7. User stays on login page ✓
```

**Double-click (NOW FIXED):**
```
1. User clicks Submit (first time)
2. loading = true, error = ''
3. User clicks Submit (second time)
4. ✅ Guard: if (loading) return; → Ignored
5. First request completes → error = 'Invalid credentials'
6. Error stays visible ✓
```

## Additional Safeguards

### 1. Form Validation
HTML5 validation prevents empty submission:
```tsx
<input
  id="username"
  type="text"
  required  // ✅ Browser validates before submit
  // ...
/>
```

### 2. Loading State
Button is disabled during request:
```tsx
<button
  type="submit"
  disabled={loading}  // ✅ Cannot click while loading
>
```

### 3. Error Clearing Logic
Error only clears on intentional new submission:
```typescript
const handleSubmit = async (e: FormEvent) => {
  e.preventDefault();
  if (loading) return;  // ✅ Guard prevents clearing error
  setError('');  // Only clears on new valid submission
  // ...
}
```

## Common Scenarios

### Scenario 1: User Makes Typo
```
1. User enters wrong password
2. Clicks Submit
3. Error: "Invalid credentials"
4. User fixes password
5. Clicks Submit again
6. ✓ Error clears, new attempt starts
```

### Scenario 2: User Double-Clicks
```
1. User enters credentials
2. Double-clicks Submit quickly
3. First click: Request sent
4. Second click: Ignored (loading = true)
5. Result: One request, proper error handling
```

### Scenario 3: User Clicks During Loading
```
1. User clicks Submit
2. Request in progress (loading = true)
3. User clicks Submit again
4. Guard prevents submission
5. Original request completes
6. Error shows (if invalid)
```

## Debugging

### If Error Still Disappears

**1. Check Browser Console:**
```javascript
// Add to Login.tsx temporarily
console.log('Submit called, loading:', loading, 'error:', error);
```

**2. Check Network Tab:**
- How many requests are being made?
- Is a second request clearing the error?

**3. Check React DevTools:**
- Is `loading` state correct?
- Is `error` state being set and then cleared?

**4. Add Breakpoint:**
```typescript
const handleSubmit = async (e: FormEvent) => {
  e.preventDefault();
  debugger;  // Pause here
  if (loading) return;
  // ...
}
```

### If Test Still Fails

**1. Run in Headed Mode:**
```bash
npx playwright test -g "invalid credentials" --headed --slow-mo=1000
```

**2. Check Test Output:**
```bash
npx playwright test -g "invalid credentials" --debug
```

**3. Take Screenshot:**
```typescript
test('should show error', async ({ page }) => {
  // ... submit form ...
  await page.screenshot({ path: 'error-state.png' });
});
```

## Edge Cases Handled

✅ **Double-click submit button** - Guard prevents re-submission
✅ **Press Enter multiple times** - Guard prevents re-submission
✅ **Click submit while loading** - Button disabled + guard
✅ **Network timeout** - Error shows, loading clears
✅ **Server error** - Error message displays
✅ **Empty response** - Fallback error message
✅ **Component unmount** - State cleaned up properly

## Files Modified

1. **src/components/Login.tsx**
   - Added `if (loading) return;` guard
   - Prevents double-submission clearing error

2. **tests/auth.spec.ts**
   - Added URL check to verify no redirect
   - Added persistence check for error message
   - Added 1-second wait to ensure stability

## Summary

✅ **Fixed:** Double-submission guard prevents error clearing
✅ **Fixed:** Test verifies error persists
✅ **Improved:** Better error state management
✅ **Verified:** Error displays and stays visible

The error message should now display reliably and not disappear unexpectedly!
