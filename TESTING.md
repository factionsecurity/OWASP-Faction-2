# UI Testing with Docker

This project includes a complete Docker-based UI testing environment that ensures tests always run with a fresh database.

## Overview

The testing environment includes:
- **Fresh MongoDB** - A new database instance for each test run
- **Backend API** - Spring Boot application
- **Frontend** - React application served by Nginx
- **Playwright Tests** - Automated UI tests

## Prerequisites

- Docker Desktop or Docker Engine (v20.10+)
- Docker Compose (v2.0+)

## Running Tests

### Linux/Mac

```bash
./run-ui-tests.sh
```

### Windows (PowerShell)

```powershell
.\run-ui-tests.ps1
```

### Manual Execution

```bash
# Build and run all services
docker-compose -f docker-compose.test.yml up --build

# Run only the tests (after services are running)
docker-compose -f docker-compose.test.yml up playwright-tests

# Clean up
docker-compose -f docker-compose.test.yml down -v
```

## How It Works

1. **Clean Start**: The script ensures no previous test containers are running
2. **Build Images**: Builds fresh Docker images for backend, frontend, and test runner
3. **Start Services**:
   - Starts MongoDB with no persistent volume (fresh database)
   - Starts backend and waits for it to be healthy
   - Starts frontend and waits for it to be healthy
4. **Run Tests**: Executes Playwright tests against the running services
5. **Cleanup**: Tears down all containers and removes volumes

## Test Reports

After running tests, reports are available at:
- **HTML Report**: `./frontend/playwright-report/index.html`
- **Test Results**: `./frontend/test-results/`

To view the HTML report:

```bash
# Linux/Mac
open ./frontend/playwright-report/index.html

# Windows
start ./frontend/playwright-report/index.html
```

## Architecture

### Services

```
┌─────────────────┐
│  Playwright     │
│  Test Runner    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐     ┌─────────────────┐
│    Frontend     │────▶│    Backend      │
│  (React/Nginx)  │     │  (Spring Boot)  │
└─────────────────┘     └────────┬────────┘
                                 │
                                 ▼
                        ┌─────────────────┐
                        │    MongoDB      │
                        │  (Fresh Instance)│
                        └─────────────────┘
```

### Network

All services run on an isolated Docker network (`test-network`), ensuring:
- Services can communicate using service names (e.g., `http://backend:8080`)
- Tests are isolated from your local environment
- No port conflicts with locally running services

### Health Checks

Each service has health checks to ensure proper startup order:

1. **MongoDB**: Checks database ping
2. **Backend**: Checks `/actuator/health` endpoint
3. **Frontend**: Checks web server availability
4. **Tests**: Only start after all services are healthy

## Configuration

### Environment Variables

The test environment uses these environment variables (configured in `docker-compose.test.yml`):

**Backend:**
- `SPRING_PROFILES_ACTIVE=test`
- `SPRING_DATA_MONGODB_URI=mongodb://admin:admin123@mongodb-test:27017/clientportal?authSource=admin`
- `JWT_SECRET=test-secret-key-for-testing-only`

**Frontend:**
- `BASE_URL=http://frontend`
- `API_URL=http://backend:8080/api/v1`

**Test Runner:**
- `TEST_SUPERADMIN_USERNAME=admin`
- `TEST_SUPERADMIN_PASSWORD=admin123`
- `CI=true`

### Customization

To modify test configuration:

1. **Timeout**: Edit `timeout` value in `run-ui-tests.sh` or `run-ui-tests.ps1`
2. **Database**: Modify `mongodb-test` service in `docker-compose.test.yml`
3. **Test Selection**: Update Playwright command in `Dockerfile.test`

## Troubleshooting

### Services fail to become healthy

Check service logs:
```bash
docker-compose -f docker-compose.test.yml logs backend
docker-compose -f docker-compose.test.yml logs frontend
docker-compose -f docker-compose.test.yml logs mongodb-test
```

### Tests fail unexpectedly

1. **Check service status**:
   ```bash
   docker-compose -f docker-compose.test.yml ps
   ```

2. **View test output**:
   ```bash
   docker-compose -f docker-compose.test.yml logs playwright-tests
   ```

3. **Run tests interactively**:
   ```bash
   # Start services
   docker-compose -f docker-compose.test.yml up -d mongodb-test backend frontend

   # Run tests with UI
   cd frontend
   npm run test:ui
   ```

### Port conflicts

If you have local services running on the same ports, stop them first:
```bash
# Stop local MongoDB
docker-compose -f backend/docker-compose.yml down

# Stop local dev servers
# (stop npm/maven processes)
```

### Clean rebuild

Force rebuild of all images:
```bash
docker-compose -f docker-compose.test.yml build --no-cache
docker-compose -f docker-compose.test.yml up --force-recreate
```

## CI/CD Integration

### GitHub Actions Example

```yaml
name: UI Tests

on: [push, pull_request]

jobs:
  ui-tests:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v3

      - name: Run UI Tests
        run: ./run-ui-tests.sh

      - name: Upload Test Results
        if: always()
        uses: actions/upload-artifact@v3
        with:
          name: playwright-report
          path: frontend/playwright-report/
```

### GitLab CI Example

```yaml
ui-tests:
  image: docker:latest
  services:
    - docker:dind
  script:
    - chmod +x run-ui-tests.sh
    - ./run-ui-tests.sh
  artifacts:
    when: always
    paths:
      - frontend/playwright-report/
      - frontend/test-results/
```

## Best Practices

1. **Always use fresh database**: Never reuse containers between test runs
2. **Run tests in isolation**: Use the provided scripts to ensure proper cleanup
3. **Check reports**: Review HTML reports for detailed test results
4. **Update tests**: Keep tests in sync with UI changes
5. **Version control**: Commit Dockerfiles and compose files to Git

## Performance Tips

1. **Build caching**: Docker will cache layers, speeding up subsequent runs
2. **Parallel tests**: Playwright runs tests in parallel by default
3. **Selective tests**: Run specific tests using Playwright's `-g` flag
4. **Resource limits**: Adjust Docker Desktop resource limits if tests are slow

## Maintenance

### Updating Dependencies

**Backend:**
```bash
cd backend
./mvnw versions:display-dependency-updates
```

**Frontend:**
```bash
cd frontend
npm outdated
```

**Playwright:**
```bash
cd frontend
npm update @playwright/test
```

### Cleaning Up Old Images

```bash
# Remove unused images
docker image prune -a

# Remove all test-related images
docker-compose -f docker-compose.test.yml down --rmi all -v
```

## Support

For issues or questions:
1. Check service logs: `docker-compose -f docker-compose.test.yml logs [service]`
2. Review test reports in `./frontend/playwright-report/`
3. Consult Playwright documentation: https://playwright.dev
