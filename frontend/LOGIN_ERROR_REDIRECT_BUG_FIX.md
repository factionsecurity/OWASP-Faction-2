# Critical Bug Fix: Login Error Not Displaying

## Issue
When entering invalid credentials, the error message never appears. Instead, the page immediately redirects/refreshes back to the login page.

## Root Cause

### The Axios Interceptor Bug

**Location:** `src/api.ts` lines 21-34

```typescript
api.interceptors.response.use(
  (response) => response,
  (error) => {
    // ❌ BUG: Catches ALL 401 errors, including login failures
    if (error.response?.status === 401 || error.response?.status === 403) {
      localStorage.removeItem('token');
      localStorage.removeItem('user');
      window.dispatchEvent(new Event('logout'));
      window.location.href = '/login';  // Redirects immediately!
    }
    return Promise.reject(error);
  }
);
```

### What Was Happening

**Login flow with invalid credentials:**

1. User enters `invaliduser` / `wrongpassword`
2. Click Submit
3. Frontend sends POST to `/api/v1/auth/login`
4. Backend returns `401 Unauthorized` with error message
5. **Axios interceptor catches the 401**
6. **Interceptor redirects to `/login`** ← Bug happens here!
7. Error never reaches Login component's catch block
8. User sees page refresh, no error message ❌

### The Interceptor's Purpose

The interceptor is meant to handle authentication failures for **authenticated API calls** (like fetching users, teams, etc.), not for the login endpoint itself.

**Intended behavior:**
- User is logged in and browsing `/users` page
- Session expires
- API call returns 401
- Interceptor logs user out and redirects to login ✅

**Unintended behavior:**
- User tries to login with invalid credentials
- Login endpoint returns 401
- Interceptor treats it as expired session
- Redirects to login (where user already is!) ❌

## Fix Applied

### Exclude Login Endpoint from Redirect Logic

**Fixed in `src/api.ts`:**

```typescript
api.interceptors.response.use(
  (response) => response,
  (error) => {
    // ✅ FIX: Skip login endpoint - let it handle its own errors
    const isLoginEndpoint = error.config?.url?.includes('/auth/login');

    if ((error.response?.status === 401 || error.response?.status === 403) && !isLoginEndpoint) {
      localStorage.removeItem('token');
      localStorage.removeItem('user');
      window.dispatchEvent(new Event('logout'));
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);
```

### How It Works Now

**Login flow with invalid credentials (FIXED):**

1. User enters `invaliduser` / `wrongpassword`
2. Click Submit
3. Frontend sends POST to `/api/v1/auth/login`
4. Backend returns `401 Unauthorized` with error message
5. Axios interceptor sees the 401
6. **Interceptor checks: Is this the login endpoint?**
7. **Yes → Skip redirect logic** ✅
8. Error propagates to Login component's catch block
9. `setError()` is called
10. **Error message displays** ✅

**Authenticated API call with expired token (UNCHANGED):**

1. User logged in, browsing `/users`
2. Session expires
3. API call to `/api/v1/users` returns 401
4. Axios interceptor sees the 401
5. **Interceptor checks: Is this the login endpoint?**
6. **No → Execute redirect logic** ✅
7. Clear localStorage
8. Redirect to `/login`
9. User must log in again

## Verification

### Manual Test

1. **Start the application:**
```bash
# Backend (Terminal 1)
cd backend
mvn spring-boot:run

# Frontend (Terminal 2)
cd frontend
npm run dev
```

2. **Open browser:** http://localhost:3000/login

3. **Enter invalid credentials:**
   - Username: `invaliduser`
   - Password: `wrongpassword`
   - Click "Sign In"

4. **Expected result:**
   - ✅ Error message appears: "Invalid credentials" (or similar)
   - ✅ Error stays visible
   - ✅ No page refresh/redirect
   - ✅ User can try again

5. **Try valid credentials:**
   - Username: `admin`
   - Password: `admin123`
   - Click "Sign In"
   - ✅ Successfully redirects to dashboard

### Automated Test

```bash
npx playwright test -g "should show error for invalid credentials" --headed
```

**Expected:**
1. ✅ Form fills with invalid credentials
2. ✅ Submit clicked
3. ✅ Error message appears
4. ✅ Error stays visible
5. ✅ Test passes

## Technical Details

### Axios Error Flow

**Before fix:**
```
Login API Call (401)
  ↓
Axios Interceptor
  ↓
if (401) → Redirect
  ↓
❌ Error never reaches catch block
```

**After fix:**
```
Login API Call (401)
  ↓
Axios Interceptor
  ↓
if (401 && !isLoginEndpoint) → Redirect
  ↓ (isLoginEndpoint = true, skip redirect)
Promise.reject(error)
  ↓
✅ Error reaches catch block
  ↓
setError() → Display message
```

### Why This Pattern is Common

Many applications use a global error interceptor for handling session expiration. The key is to **exclude authentication endpoints** from the redirect logic:

```typescript
const authEndpoints = ['/auth/login', '/auth/register', '/auth/reset-password'];
const isAuthEndpoint = authEndpoints.some(ep => error.config?.url?.includes(ep));

if ((401 or 403) && !isAuthEndpoint) {
  // Redirect to login
}
```

### Alternative Approaches

#### Option 1: Use Separate Axios Instance for Auth
```typescript
// No interceptor
const authApi = axios.create({ baseURL: '/api/v1' });

// With interceptor
const api = axios.create({ baseURL: '/api/v1' });
api.interceptors.response.use(...);

// Use authApi for login, api for everything else
authApi.post('/auth/login', credentials);
```

#### Option 2: Check Response Headers
```typescript
api.interceptors.response.use(
  (response) => response,
  (error) => {
    // Backend sends special header for auth endpoints
    const isAuthEndpoint = error.response?.headers['x-auth-endpoint'] === 'true';

    if ((401 or 403) && !isAuthEndpoint) {
      // Redirect
    }
  }
);
```

#### Option 3: Custom Error Codes
```typescript
// Backend returns 401 for invalid login
// Backend returns 419 for expired session

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 419) {  // Session expired
      // Redirect to login
    }
    // 401 errors propagate normally
  }
);
```

## Impact

### Before Fix
- ❌ Users cannot see why login failed
- ❌ Confusing user experience (page just refreshes)
- ❌ No way to know if credentials are wrong
- ❌ Tests failing for error display

### After Fix
- ✅ Clear error messages for login failures
- ✅ User knows exactly what went wrong
- ✅ Can retry with correct credentials
- ✅ Tests pass correctly
- ✅ Better UX and debugging

## Related Issues This Fixes

1. **"Error message disappears immediately"** - It wasn't disappearing; it never appeared
2. **"Page refreshes after login attempt"** - Interceptor was causing redirect
3. **"Can't see backend error messages"** - Errors intercepted before reaching UI
4. **"Tests fail for invalid credentials"** - Real bug, not test issue

## Security Considerations

### Does This Expose Any Vulnerabilities?

**No.** The fix:
- ✅ Still handles expired sessions properly
- ✅ Still redirects on 401/403 for authenticated endpoints
- ✅ Only skips redirect for login endpoint (which should handle its own errors)
- ✅ Doesn't leak any sensitive information
- ✅ Follows standard authentication patterns

### Session Expiration Still Works

When a user's session expires:
```typescript
// User tries to fetch users
GET /api/v1/users
← 401 Unauthorized

// Interceptor checks
isLoginEndpoint?  // false (it's /users)
Redirect to /login  // ✅ Still works!
```

## Testing Checklist

- [x] Invalid credentials show error message
- [x] Valid credentials log in successfully
- [x] Session expiration still redirects to login
- [x] 403 errors still handled correctly
- [x] Tests pass for error display
- [x] No page refresh on login error
- [x] Error message persists until next attempt

## Files Modified

**src/api.ts** - Line 23-24
- Added check: `const isLoginEndpoint = error.config?.url?.includes('/auth/login');`
- Updated condition: `if ((401 || 403) && !isLoginEndpoint)`

## Summary

✅ **Critical Bug:** Login errors triggered redirect instead of showing error message
✅ **Root Cause:** Axios interceptor caught login 401s and redirected
✅ **Fix:** Exclude login endpoint from interceptor redirect logic
✅ **Impact:** Error messages now display correctly
✅ **Status:** Bug fixed, tests passing

Users can now see error messages when login fails! 🎉
