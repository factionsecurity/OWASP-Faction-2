# Testing Quick Start Guide

## Prerequisites

1. **Backend is running** at `http://localhost:8080`
2. **Frontend is built** (run `npm run build`)
3. **Test user exists**: username `admin`, password `admin123`

## Installation

Install Playwright browsers (one-time setup):

```bash
npx playwright install
```

## Running Tests

### 1. Quick Test Run

Run all tests in headless mode:

```bash
npm test
```

Expected output:
```
Running 25 tests using 4 workers
  ✓ auth.spec.ts:10:5 › should successfully login as superadmin (2s)
  ✓ users.spec.ts:15:5 › should create a new user (3s)
  ...
  25 passed (1m)
```

### 2. Watch Tests Run

See the browser while tests execute:

```bash
npm run test:headed
```

### 3. Interactive UI Mode

Best for development - run tests interactively:

```bash
npm run test:ui
```

Features:
- Click tests to run them
- See live browser preview
- Debug step by step
- Filter tests by name

### 4. Run Specific Tests

Authentication tests only:
```bash
npm run test:auth
```

User management tests only:
```bash
npm run test:users
```

Single test file:
```bash
npx playwright test tests/auth.spec.ts
```

Single test by name:
```bash
npx playwright test -g "should successfully login"
```

### 5. Debug Tests

Run in debug mode with Playwright Inspector:

```bash
npm run test:debug
```

This opens a debugger where you can:
- Step through each line
- Pause execution
- Inspect elements
- View console logs

## Test Results

### View HTML Report

After tests complete:

```bash
npm run test:report
```

This opens an HTML report showing:
- Pass/fail status for each test
- Execution time
- Screenshots of failures
- Video recordings
- Step-by-step traces

### Test Artifacts

Failed tests automatically generate:

**Screenshots**: `test-results/[test-name]/test-failed-1.png`
**Videos**: `test-results/[test-name]/video.webm`
**Traces**: `test-results/[test-name]/trace.zip`

## Common Issues

### Issue: "No tests found"

**Fix**: Make sure you're in the frontend directory:
```bash
cd /home/nullop/Code/Faction\ Projects/claude-version/frontend
npm test
```

### Issue: "Error: page.goto: net::ERR_CONNECTION_REFUSED"

**Fix**: Start the frontend dev server:
```bash
npm run dev
```

The Playwright config will automatically start the dev server, but if it fails:
1. Check port 3000 is not already in use
2. Manually start the dev server in a separate terminal

### Issue: "Timeout waiting for element"

**Fix**: Increase timeout or check backend is running:
```bash
# Check backend is running
curl http://localhost:8080/health

# Increase timeout in test
await expect(element).toBeVisible({ timeout: 30000 });
```

### Issue: "Login test fails"

**Fix**: Verify test credentials work:
```bash
# Test login via API
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

### Issue: "Tests are flaky"

**Fix**: Run tests in series instead of parallel:
```bash
npx playwright test --workers=1
```

## Test Development Workflow

### 1. Write Test

Create or edit test file in `tests/` directory:

```typescript
import { test, expect } from '@playwright/test';

test('my new test', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('h1')).toBeVisible();
});
```

### 2. Run in UI Mode

```bash
npm run test:ui
```

- Click your test to run it
- See results immediately
- Edit test and re-run

### 3. Debug if Needed

```bash
npm run test:debug
```

- Step through your test
- Find issues
- Fix test

### 4. Run All Tests

```bash
npm test
```

Verify your test doesn't break others.

## Browser Testing

Test in specific browsers:

```bash
# Chrome/Edge
npm run test:chromium

# Firefox
npm run test:firefox

# Safari
npm run test:webkit
```

Test in all browsers:
```bash
npm test
```

## Test Coverage

Current test coverage:

### Authentication (8 tests)
- ✅ Login form display
- ✅ Validation
- ✅ Successful login
- ✅ Invalid credentials
- ✅ Logout
- ✅ Session persistence
- ✅ Protected routes
- ✅ Loading states

### User Management (20+ tests)
- ✅ Create user
- ✅ Edit user
- ✅ Delete user
- ✅ Search users
- ✅ Table interactions
- ✅ Validation
- ✅ Error handling

## Next Steps

1. **Run tests locally** to verify setup
2. **Add to CI/CD** pipeline
3. **Write more tests** for Teams, Roles, etc.
4. **Review failures** regularly

## Performance Tips

### Fast Feedback Loop

During development:

```bash
# Run specific test file
npx playwright test tests/users.spec.ts

# Run tests matching pattern
npx playwright test -g "create user"

# Run in headed mode to see what's happening
npx playwright test --headed -g "create user"
```

### Parallel Execution

Speed up test runs:

```bash
# Use more workers (default: CPU cores)
npx playwright test --workers=8
```

### Skip Slow Tests

```bash
# Run only fast tests during development
npx playwright test --grep-invert @slow
```

## Example Output

### Successful Run

```
Running 25 tests using 4 workers

  25 passed (1m 23s)

To open last HTML report run:
  npx playwright show-report
```

### Failed Test

```
Running 25 tests using 4 workers

  24 passed
  1 failed
    users.spec.ts:145:7 › should delete user

  Error: expect(received).toBe(expected)
    Expected: false
    Received: true

To open last HTML report run:
  npx playwright show-report
```

## Continuous Integration

Tests run automatically in CI/CD:

1. **On Push**: Tests run for every commit
2. **On PR**: Tests must pass before merge
3. **Nightly**: Full test suite runs daily

See `.github/workflows/` for CI configuration.

## Getting Help

### Documentation
- Full docs: [TESTING.md](./TESTING.md)
- Playwright docs: https://playwright.dev

### Common Commands Cheat Sheet

```bash
# Install browsers
npx playwright install

# Run all tests
npm test

# Run with UI
npm run test:ui

# Run in headed mode
npm run test:headed

# Debug tests
npm run test:debug

# Run specific tests
npm run test:auth
npm run test:users

# View report
npm run test:report

# Run single test
npx playwright test -g "login"

# Run in specific browser
npm run test:chromium
npm run test:firefox
npm run test:webkit
```

## Success Criteria

✅ All tests pass locally
✅ Tests run in < 2 minutes
✅ No flaky tests (run tests 3x to verify)
✅ Screenshots captured on failure
✅ HTML report generated

You're ready to go! Start with:
```bash
npm run test:ui
```
