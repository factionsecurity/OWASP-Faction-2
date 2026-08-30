# Docker Test Environment - Quick Start

## Prerequisites Check

Before running tests, ensure you have:

```bash
# Check Docker
docker --version
# Should show: Docker version 20.10+ or higher

# Check Docker Compose
docker compose version
# Should show: Docker Compose version v2.0+ or higher
```

## Step-by-Step First Run

### 1. Build Images (Test Build)

Test that all images can build successfully:

```bash
cd "/home/nullop/Code/Faction Projects/claude-version"

# Test backend build
docker compose -f docker-compose.test.yml build backend

# Test frontend build
docker compose -f docker-compose.test.yml build frontend

# Test playwright build
docker compose -f docker-compose.test.yml build playwright-tests
```

If any build fails, check the error messages.

### 2. Run Full Test Suite

Once builds succeed, run the full test suite:

```bash
./run-ui-tests.sh
```

## Common Issues & Solutions

### Issue: "permission denied: ./run-ui-tests.sh"

**Solution:**
```bash
chmod +x run-ui-tests.sh
./run-ui-tests.sh
```

### Issue: "docker: command not found"

**Solution:** Install Docker Desktop from https://www.docker.com/products/docker-desktop

### Issue: Backend build fails with Maven errors

**Solution:** The Dockerfile now uses the official Maven image, so this should work. If you see errors:
```bash
# Check if pom.xml exists
ls backend/pom.xml

# Check Docker logs
docker compose -f docker-compose.test.yml logs backend
```

### Issue: Frontend build fails with npm errors

**Solution:**
```bash
# Verify package.json exists
ls frontend/package.json

# Check for syntax errors in package.json
cat frontend/package.json | jq .
```

### Issue: Services timeout during health checks

**Solution:**
```bash
# Increase timeout in run-ui-tests.sh
# Change line: timeout=180
# To: timeout=300

# Or check service logs
docker compose -f docker-compose.test.yml logs backend
docker compose -f docker-compose.test.yml logs frontend
```

### Issue: MongoDB connection errors

**Solution:**
```bash
# Check MongoDB is running
docker compose -f docker-compose.test.yml ps mongodb-test

# Check MongoDB logs
docker compose -f docker-compose.test.yml logs mongodb-test

# Verify connection string in backend logs
docker compose -f docker-compose.test.yml logs backend | grep mongodb
```

### Issue: Port conflicts

**Solution:**
```bash
# Stop local development services
docker compose -f backend/docker-compose.yml down

# Stop any local MongoDB
sudo systemctl stop mongodb  # or mongod
```

## Manual Testing (Without Script)

If the automated script doesn't work, run manually:

```bash
# 1. Start services
docker compose -f docker-compose.test.yml up -d mongodb-test backend frontend

# 2. Wait for health (check every few seconds)
docker compose -f docker-compose.test.yml ps

# 3. When all services are "healthy", run tests
docker compose -f docker-compose.test.yml up playwright-tests

# 4. Cleanup
docker compose -f docker-compose.test.yml down -v
```

## Verify Each Service Individually

### Test MongoDB

```bash
# Start MongoDB
docker compose -f docker-compose.test.yml up -d mongodb-test

# Check health
docker compose -f docker-compose.test.yml ps mongodb-test

# Should show "(healthy)" in status

# Test connection
docker exec test-mongodb mongosh \
  -u admin -p admin123 --authenticationDatabase admin \
  --eval "db.adminCommand('ping')"

# Should output: { ok: 1 }

# Cleanup
docker compose -f docker-compose.test.yml down -v
```

### Test Backend

```bash
# Start MongoDB and Backend
docker compose -f docker-compose.test.yml up -d mongodb-test backend

# Wait for backend to be healthy (may take 30-60 seconds)
watch -n 2 'docker compose -f docker-compose.test.yml ps'

# Test backend health endpoint
curl http://localhost:8080/actuator/health
# Should return: {"status":"UP"}

# Test API login
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
# Should return a JWT token

# Cleanup
docker compose -f docker-compose.test.yml down -v
```

### Test Frontend

```bash
# Start all services
docker compose -f docker-compose.test.yml up -d mongodb-test backend frontend

# Wait for all to be healthy
watch -n 2 'docker compose -f docker-compose.test.yml ps'

# Test frontend
curl http://localhost:80
# Should return HTML

# View in browser (if port 80 is exposed)
# Open: http://localhost

# Cleanup
docker compose -f docker-compose.test.yml down -v
```

## Debug Mode

Run services in foreground to see logs:

```bash
# Start services in foreground (logs visible)
docker compose -f docker-compose.test.yml up mongodb-test backend frontend

# In another terminal, when ready:
docker compose -f docker-compose.test.yml up playwright-tests

# Cleanup (Ctrl+C in first terminal, then):
docker compose -f docker-compose.test.yml down -v
```

## View Logs

```bash
# All services
docker compose -f docker-compose.test.yml logs

# Specific service
docker compose -f docker-compose.test.yml logs backend
docker compose -f docker-compose.test.yml logs frontend
docker compose -f docker-compose.test.yml logs mongodb-test

# Follow logs (live)
docker compose -f docker-compose.test.yml logs -f backend
```

## Clean Everything

If things get stuck, clean all Docker resources:

```bash
# Stop and remove test containers
docker compose -f docker-compose.test.yml down -v

# Remove images
docker compose -f docker-compose.test.yml down --rmi all -v

# Nuclear option - clean all Docker (BE CAREFUL!)
# docker system prune -a --volumes
```

## Success Indicators

When everything works, you should see:

1. ✅ All images build without errors
2. ✅ MongoDB shows "(healthy)" status
3. ✅ Backend shows "(healthy)" status
4. ✅ Frontend shows "(healthy)" status
5. ✅ Playwright tests run and complete
6. ✅ Test report generated in `frontend/playwright-report/`

## Next Steps

Once the basic setup works:

1. Run specific tests: `npx playwright test organizations.spec.ts`
2. View test report: `open frontend/playwright-report/index.html`
3. Add to CI/CD: See examples in `TESTING.md`
4. Customize configuration: Edit `docker-compose.test.yml`

## Getting Help

If issues persist:

1. Check service logs (see "View Logs" section above)
2. Verify Docker resources: `docker stats`
3. Check Docker disk space: `docker system df`
4. Review full test output in `frontend/test-results/`
5. Run tests locally (non-Docker): `cd frontend && npm run test`

## Quick Reference

```bash
# Build images
docker compose -f docker-compose.test.yml build

# Start services
docker compose -f docker-compose.test.yml up -d

# Check status
docker compose -f docker-compose.test.yml ps

# Run tests
docker compose -f docker-compose.test.yml up playwright-tests

# View logs
docker compose -f docker-compose.test.yml logs -f [service]

# Cleanup
docker compose -f docker-compose.test.yml down -v

# Full test run
./run-ui-tests.sh
```
