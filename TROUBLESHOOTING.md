# Troubleshooting Guide

## Login Returns 500 Error

### Problem
When attempting to login from the frontend, you receive a 500 Internal Server Error.

### Root Cause
The backend wasn't configured to allow CORS (Cross-Origin Resource Sharing) requests from the frontend running on `localhost:3000`.

### Solution
I've added CORS configuration to the backend. Follow these steps:

#### 1. Stop the Backend
Press `Ctrl+C` in the terminal running the backend, or:
```bash
lsof -ti:8080 | xargs kill -9
```

#### 2. Rebuild the Backend
```bash
cd backend
mvn clean install
```

#### 3. Restart the Backend
```bash
mvn spring-boot:run
```

#### 4. Verify CORS is Working
Once the backend starts, try logging in again from the frontend.

## Additional Checks

### Check Backend is Running
```bash
curl http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

Expected response:
```json
{
  "token": "eyJhbG...",
  "tokenType": "Bearer",
  "expiresIn": 86400,
  "username": "admin",
  "authorities": ["super_admin"]
}
```

### Check Frontend Console
Open browser DevTools (F12) → Console tab and look for errors.

### Check Network Tab
1. Open DevTools (F12) → Network tab
2. Try logging in
3. Look for the request to `/api/v1/auth/login`
4. Check the response status and body

## Common Issues

### Issue: Cannot connect to backend

**Symptoms:**
- Network error in browser console
- "ERR_CONNECTION_REFUSED"

**Solution:**
Make sure the backend is running:
```bash
cd backend
mvn spring-boot:run
```

### Issue: MongoDB not running

**Symptoms:**
- Backend fails to start
- Error: "MongoSocketOpenException"

**Solution:**
```bash
cd backend
docker compose up -d
docker compose ps  # Verify MongoDB is running
```

### Issue: Port 8080 already in use

**Symptoms:**
- Backend fails to start
- Error: "Port 8080 is already in use"

**Solution:**
```bash
# Find and kill the process using port 8080
lsof -ti:8080 | xargs kill -9

# Or use a different port
mvn spring-boot:run -Dserver.port=8081

# Update frontend proxy in vite.config.ts if using different port
```

### Issue: Frontend not loading

**Symptoms:**
- Blank page
- "Cannot GET /" error

**Solution:**
```bash
cd frontend
npm install
npm run dev
```

### Issue: Logo not showing

**Symptoms:**
- Broken image on login page
- 404 error for logo file

**Solution:**
```bash
cp ~/Pictures/faction-white-logo.png frontend/public/
```

### Issue: Invalid credentials

**Symptoms:**
- Login form shows "Invalid credentials" error
- Status 401 from backend

**Solution:**
Verify you're using the correct default credentials:
- Username: `admin`
- Password: `admin123`

### Issue: Token expired

**Symptoms:**
- Redirected to login after some time
- 401/403 errors in console

**Solution:**
This is normal behavior. Tokens expire after 24 hours. Just login again.

## Debugging Steps

### 1. Check Backend Logs
Look at the terminal where `mvn spring-boot:run` is running for error messages.

### 2. Check Frontend Logs
Open browser DevTools (F12) → Console tab for JavaScript errors.

### 3. Check Network Requests
Open DevTools (F12) → Network tab → Try the action → Inspect failed requests.

### 4. Test Backend Directly
Use curl or Postman to test the backend API directly:
```bash
# Login
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# Get users (with token)
curl -X GET http://localhost:8080/api/v1/users \
  -H "Authorization: Bearer YOUR_TOKEN_HERE"
```

### 5. Clear Browser Cache
Sometimes cached data can cause issues:
1. Open DevTools (F12)
2. Right-click the refresh button
3. Select "Empty Cache and Hard Reload"

### 6. Check MongoDB
```bash
cd backend
docker compose ps
docker compose logs mongodb
```

## Files Changed

The following files were created/modified to fix the CORS issue:

1. **backend/src/main/java/com/faction/clientportal/config/CorsConfig.java** (NEW)
   - Configures CORS to allow requests from localhost:3000
   - Allows all HTTP methods
   - Allows credentials

2. **backend/src/main/java/com/faction/clientportal/config/SecurityConfig.java** (MODIFIED)
   - Added CORS configuration to Spring Security
   - Enables CORS for all endpoints

## Still Having Issues?

If you're still experiencing problems after following this guide:

1. **Check versions:**
   ```bash
   java -version  # Should be 17+
   mvn -version   # Should be 3.9.9+
   node -version  # Should be 20.11.0
   ```

2. **Clean rebuild:**
   ```bash
   # Backend
   cd backend
   mvn clean install -U

   # Frontend
   cd frontend
   rm -rf node_modules package-lock.json
   npm install
   ```

3. **Check ports:**
   ```bash
   # Backend should be on 8080
   lsof -ti:8080

   # Frontend should be on 3000
   lsof -ti:3000

   # MongoDB should be on 27017
   lsof -ti:27017
   ```

4. **Review logs carefully:**
   - Backend logs in the `mvn spring-boot:run` terminal
   - Frontend logs in the `npm run dev` terminal
   - Browser console (F12 → Console)
   - Browser network tab (F12 → Network)

---

**Most Common Fix:** After adding CORS config, just restart the backend and try again!
