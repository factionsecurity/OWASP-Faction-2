#!/bin/bash

set -e

echo "Testing Backend Health Check"
echo "========================================"

# Cleanup
echo "Cleaning up any existing containers..."
docker compose -f docker-compose.test.yml down -v 2>/dev/null || true

# Start TimescaleDB and Backend
echo "Starting TimescaleDB and Backend..."
docker compose -f docker-compose.test.yml up -d timescaledb-test backend

# Wait and check health
echo "Waiting for backend to be healthy (max 120 seconds)..."
timeout=120
elapsed=0

while [ $elapsed -lt $timeout ]; do
    backend_health=$(docker inspect --format='{{.State.Health.Status}}' test-backend 2>/dev/null || echo "starting")

    echo "  [$elapsed s] Backend health: $backend_health"

    if [ "$backend_health" = "healthy" ]; then
        echo "✅ Backend is healthy!"

        # Test the health endpoint
        echo ""
        echo "Testing health endpoint directly..."
        docker exec test-backend wget -qO- http://localhost:8080/actuator/health || echo "Health check failed"

        # Test login endpoint
        echo ""
        echo "Testing login endpoint..."
        docker exec test-backend wget -qO- --post-data='{"username":"admin","password":"admin123"}' \
          --header='Content-Type: application/json' \
          http://localhost:8080/api/v1/auth/login 2>/dev/null || echo "Login test failed"

        # Cleanup
        echo ""
        echo "Cleaning up..."
        docker compose -f docker-compose.test.yml down -v

        echo ""
        echo "========================================"
        echo "✅ Health check test PASSED!"
        exit 0
    fi

    sleep 5
    elapsed=$((elapsed + 5))
done

echo ""
echo "❌ Backend failed to become healthy within $timeout seconds"
echo ""
echo "Backend logs:"
docker compose -f docker-compose.test.yml logs backend

# Cleanup
docker compose -f docker-compose.test.yml down -v

exit 1
