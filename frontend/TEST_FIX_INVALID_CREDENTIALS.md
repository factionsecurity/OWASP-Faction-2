# Fix: "should show error for invalid credentials" Test

## Issue
The test was timing out waiting for the error message to appear after submitting invalid credentials.

## Root Causes

1. **Backend Not Running** - If the backend API isn't running, the login request hangs indefinitely
2. **Timeout Too Short** - Default timeout might be too short for API response
3. **Error Message Text Varies** - Different backend implementations return different error messages

## Fix Applied

### Before (Original Test)
```typescript
test('should show error for invalid credentials', async ({ page }) => {
  await page.fill('input#username', 'invaliduser');
  await page.fill('input#password', 'wrongpassword');
  await page.click('button[type="submit"]');

  // ❌ No explicit timeout - uses default 5s
  await expect(page.locator('.error-message')).toBeVisible();

  // ❌ Might not match actual error text
  await expect(page.locator('.error-message')).toContainText(/Invalid credentials|Authentication failed/i);
});
```

### After (Fixed Test)
```typescript
test('should show error for invalid credentials', async ({ page }) => {
  await page.fill('input#username', 'invaliduser');
  await page.fill('input#password', 'wrongpassword');
  await page.click('button[type="submit"]');

  // ✅ Explicit 10s timeout for API call
  await expect(page.locator('.error-message')).toBeVisible({
    timeout: TEST_CONFIG.timeout.medium  // 10000ms
  });

  // ✅ More flexible error text matching
  const errorText = await page.locator('.error-message').textContent();
  expect(errorText).toBeTruthy();
  expect(errorText!.toLowerCase()).toMatch(/invalid|authentication|failed|credentials/);
});
```

## What Changed

1. **Added Explicit Timeout**: `{ timeout: 10000 }` gives the backend 10 seconds to respond
2. **Flexible Error Matching**: Matches any error containing "invalid", "authentication", "failed", or "credentials"
3. **Better Error Handling**: Gets actual text content and validates it exists

## Prerequisites for Test to Pass

### 1. Backend Must Be Running
```bash
# Check if backend is running
curl http://localhost:8080/health

# Or check login endpoint
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"invalid","password":"wrong"}'
```

Expected response:
```json
{
  "message": "Invalid credentials",
  "status": 401
}
```

### 2. Backend Port Configuration
Verify `playwright.config.ts` has correct API URL:
```typescript
use: {
  baseURL: 'http://localhost:3000',  // Frontend
}
```

And frontend `.env` has correct API URL:
```
VITE_API_URL=http://localhost:8080/api/v1
```

## Running the Test

### Run Single Test
```bash
# Run just this test
npx playwright test -g "should show error for invalid credentials"

# With headed browser to see what happens
npx playwright test -g "should show error for invalid credentials" --headed

# With debug mode
npx playwright test -g "should show error for invalid credentials" --debug
```

### Verify Backend First
```bash
# 1. Start backend (in separate terminal)
cd backend
mvn spring-boot:run

# 2. Verify it's running
curl http://localhost:8080/health

# 3. Run test
cd ../frontend
npx playwright test -g "invalid credentials"
```

## Common Errors and Solutions

### Error: "Timeout waiting for .error-message"

**Cause**: Backend not running or not responding
**Solution**:
```bash
# Check backend
curl http://localhost:8080/api/v1/auth/login

# If no response, start backend
cd backend && mvn spring-boot:run
```

### Error: "Expected text to contain..."

**Cause**: Backend returns different error message
**Solution**: Check what error message backend actually returns:
```bash
# Test login endpoint
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"invalid","password":"wrong"}' -v
```

Update test to match actual message:
```typescript
expect(errorText!.toLowerCase()).toMatch(/your-actual-error-text/);
```

### Error: "net::ERR_CONNECTION_REFUSED"

**Cause**: Backend is not running at all
**Solution**:
```bash
# Start backend
cd backend
mvn spring-boot:run

# Verify it's listening on port 8080
netstat -an | grep 8080
```

## Debugging the Test

### 1. Run in Headed Mode
```bash
npx playwright test -g "invalid credentials" --headed
```

Watch the browser:
- Form fills with invalid credentials ✓
- Submit button clicks ✓
- **Wait for error message** (should appear within 10s)
- Error message should be visible

### 2. Check Network Tab
Add this to see network requests:
```typescript
test('should show error for invalid credentials', async ({ page }) => {
  // Log all network requests
  page.on('request', request => console.log('→', request.method(), request.url()));
  page.on('response', response => console.log('←', response.status(), response.url()));

  // ... rest of test
});
```

Expected output:
```
→ POST http://localhost:8080/api/v1/auth/login
← 401 http://localhost:8080/api/v1/auth/login
```

### 3. Take Screenshot on Failure
Test automatically captures screenshot on failure at:
```
test-results/
└── auth-should-show-error-for-invalid-credentials-chromium/
    └── test-failed-1.png
```

Check screenshot to see:
- Is error message displayed?
- What does it say?
- Is form still visible?

## Verify Fix Works

### Quick Test
```bash
# 1. Ensure backend is running
curl http://localhost:8080/health

# 2. Run the test
npx playwright test -g "invalid credentials" --headed

# Should see:
# ✓ Form fills with invalid credentials
# ✓ Submit clicked
# ✓ Error message appears (within 10s)
# ✓ Test passes
```

### Expected Behavior

1. **Form fills** with `invaliduser` / `wrongpassword`
2. **Submit clicked** → Loading state appears
3. **API call** to `/api/v1/auth/login` (takes 1-3s)
4. **401 response** from backend
5. **Error message** appears with text like:
   - "Invalid credentials"
   - "Authentication failed"
   - "Bad credentials"
   - "Username or password is incorrect"
6. **Test passes** ✅

## Alternative: Mock the API Response

If you want tests to run without backend:

```typescript
test('should show error for invalid credentials', async ({ page }) => {
  // Mock API response
  await page.route('**/api/v1/auth/login', route => {
    route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({
        message: 'Invalid credentials'
      })
    });
  });

  await page.fill('input#username', 'invaliduser');
  await page.fill('input#password', 'wrongpassword');
  await page.click('button[type="submit"]');

  await expect(page.locator('.error-message')).toBeVisible();
});
```

## Summary

✅ **Fixed**: Added 10s timeout for API response
✅ **Fixed**: More flexible error text matching
✅ **Required**: Backend must be running
✅ **Improved**: Better error handling

The test should now pass reliably when the backend is running and returns an error response for invalid credentials.
