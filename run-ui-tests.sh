#!/bin/bash

set -e

echo "=========================================="
echo "Starting UI Tests with Fresh Database"
echo "=========================================="

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Cleanup function
cleanup() {
    echo -e "\n${YELLOW}Cleaning up containers...${NC}"
    docker compose -f docker-compose.test.yml down -v
}

# Set trap to cleanup on exit
trap cleanup EXIT

# Clean up any existing test containers
echo -e "${YELLOW}Stopping any existing test containers...${NC}"
docker compose -f docker-compose.test.yml down -v 2>/dev/null || true

# Build and start services
echo -e "${YELLOW}Building Docker images...${NC}"
docker compose -f docker-compose.test.yml build --no-cache

echo -e "${YELLOW}Starting services...${NC}"
docker compose -f docker-compose.test.yml up -d timescaledb-test backend frontend

echo -e "${YELLOW}Waiting for services to be healthy...${NC}"
echo "This may take a minute..."

# Wait for services to be healthy
timeout=180
elapsed=0
while [ $elapsed -lt $timeout ]; do
    if docker compose -f docker-compose.test.yml ps | grep -q "healthy"; then
        frontend_health=$(docker inspect --format='{{.State.Health.Status}}' test-frontend 2>/dev/null || echo "starting")
        backend_health=$(docker inspect --format='{{.State.Health.Status}}' test-backend 2>/dev/null || echo "starting")

        if [ "$frontend_health" = "healthy" ] && [ "$backend_health" = "healthy" ]; then
            echo -e "${GREEN}All services are healthy!${NC}"
            break
        fi
    fi

    echo -n "."
    sleep 2
    elapsed=$((elapsed + 2))
done

if [ $elapsed -ge $timeout ]; then
    echo -e "\n${RED}Services failed to become healthy within ${timeout} seconds${NC}"
    echo "Service status:"
    docker compose -f docker-compose.test.yml ps
    echo -e "\nBackend logs:"
    docker compose -f docker-compose.test.yml logs backend
    exit 1
fi

# Give it a few more seconds for the app to fully initialize
echo -e "${YELLOW}Waiting for application to fully initialize...${NC}"
sleep 5

# Run Playwright tests
echo -e "${GREEN}Running Playwright tests...${NC}"
docker compose -f docker-compose.test.yml up --exit-code-from playwright-tests playwright-tests

# Capture exit code
TEST_EXIT_CODE=$?

# Show results
echo ""
echo "=========================================="
if [ $TEST_EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}✓ All tests passed!${NC}"
else
    echo -e "${RED}✗ Tests failed with exit code: $TEST_EXIT_CODE${NC}"
fi
echo "=========================================="

# Show test report location
echo -e "\n${YELLOW}Test reports available at:${NC}"
echo "  - HTML Report: ./frontend/playwright-report/index.html"
echo "  - Test Results: ./frontend/test-results/"

exit $TEST_EXIT_CODE
