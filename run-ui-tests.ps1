# PowerShell script to run UI tests with fresh database

Write-Host "==========================================" -ForegroundColor Yellow
Write-Host "Starting UI Tests with Fresh Database" -ForegroundColor Yellow
Write-Host "==========================================" -ForegroundColor Yellow

# Cleanup function
function Cleanup {
    Write-Host "`nCleaning up containers..." -ForegroundColor Yellow
    docker-compose -f docker-compose.test.yml down -v
}

# Set cleanup to run on exit
try {
    # Clean up any existing test containers
    Write-Host "Stopping any existing test containers..." -ForegroundColor Yellow
    docker-compose -f docker-compose.test.yml down -v 2>$null

    # Build and start services
    Write-Host "Building Docker images..." -ForegroundColor Yellow
    docker-compose -f docker-compose.test.yml build --no-cache

    Write-Host "Starting services..." -ForegroundColor Yellow
    docker-compose -f docker-compose.test.yml up -d mongodb-test backend frontend

    Write-Host "Waiting for services to be healthy..." -ForegroundColor Yellow
    Write-Host "This may take a minute..."

    # Wait for services to be healthy
    $timeout = 180
    $elapsed = 0
    $healthy = $false

    while ($elapsed -lt $timeout) {
        $frontendHealth = docker inspect --format='{{.State.Health.Status}}' test-frontend 2>$null
        $backendHealth = docker inspect --format='{{.State.Health.Status}}' test-backend 2>$null

        if ($frontendHealth -eq "healthy" -and $backendHealth -eq "healthy") {
            Write-Host "`nAll services are healthy!" -ForegroundColor Green
            $healthy = $true
            break
        }

        Write-Host "." -NoNewline
        Start-Sleep -Seconds 2
        $elapsed += 2
    }

    if (-not $healthy) {
        Write-Host "`nServices failed to become healthy within $timeout seconds" -ForegroundColor Red
        Write-Host "Service status:"
        docker-compose -f docker-compose.test.yml ps
        Write-Host "`nBackend logs:"
        docker-compose -f docker-compose.test.yml logs backend
        exit 1
    }

    # Give it a few more seconds for the app to fully initialize
    Write-Host "Waiting for application to fully initialize..." -ForegroundColor Yellow
    Start-Sleep -Seconds 5

    # Run Playwright tests
    Write-Host "Running Playwright tests..." -ForegroundColor Green
    docker-compose -f docker-compose.test.yml up --exit-code-from playwright-tests playwright-tests

    $testExitCode = $LASTEXITCODE

    # Show results
    Write-Host ""
    Write-Host "==========================================" -ForegroundColor Yellow
    if ($testExitCode -eq 0) {
        Write-Host "✓ All tests passed!" -ForegroundColor Green
    } else {
        Write-Host "✗ Tests failed with exit code: $testExitCode" -ForegroundColor Red
    }
    Write-Host "==========================================" -ForegroundColor Yellow

    # Show test report location
    Write-Host "`nTest reports available at:" -ForegroundColor Yellow
    Write-Host "  - HTML Report: .\frontend\playwright-report\index.html"
    Write-Host "  - Test Results: .\frontend\test-results\"

    exit $testExitCode
}
finally {
    Cleanup
}
