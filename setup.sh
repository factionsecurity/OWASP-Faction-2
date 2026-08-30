#!/bin/bash

set -e

echo "========================================="
echo "FACTION Admin Portal - Setup Script"
echo "========================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored output
print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_info() {
    echo -e "${YELLOW}ℹ $1${NC}"
}

# Check if we're in the right directory
if [ ! -d "backend" ] || [ ! -d "frontend" ]; then
    print_error "Error: Please run this script from the project root directory"
    exit 1
fi

print_info "Step 1: Checking prerequisites..."

# Check Java
if command -v java &> /dev/null; then
    JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -ge "17" ]; then
        print_success "Java $JAVA_VERSION found"
    else
        print_error "Java 17 or higher is required"
        exit 1
    fi
else
    print_error "Java not found. Please install Java 17+"
    exit 1
fi

# Check Maven
if command -v mvn &> /dev/null; then
    print_success "Maven found"
else
    print_error "Maven not found. Please install Maven 3.9.9+"
    exit 1
fi

# Check Docker
if command -v docker &> /dev/null; then
    print_success "Docker found"
else
    print_error "Docker not found. Please install Docker"
    exit 1
fi

# Check Node.js
if command -v node &> /dev/null; then
    NODE_VERSION=$(node --version)
    print_success "Node.js $NODE_VERSION found"
else
    print_error "Node.js not found. Please install Node.js 20.11.0"
    exit 1
fi

echo ""
print_info "Step 2: Starting TimescaleDB..."
cd backend
if docker compose up -d; then
    print_success "TimescaleDB started"
else
    print_error "Failed to start TimescaleDB"
    exit 1
fi

echo ""
print_info "Step 3: Waiting for TimescaleDB to be ready..."
sleep 5
print_success "TimescaleDB is ready"

echo ""
print_info "Step 4: Building backend..."
if mvn clean install -DskipTests; then
    print_success "Backend built successfully"
else
    print_error "Backend build failed"
    exit 1
fi

echo ""
print_info "Step 5: Setting up frontend..."
cd ../frontend

# Copy logo if it exists
if [ -f ~/Pictures/faction-white-logo.png ]; then
    mkdir -p public
    cp ~/Pictures/faction-white-logo.png public/
    print_success "Logo copied"
else
    print_error "Logo not found at ~/Pictures/faction-white-logo.png"
    print_info "Please copy your logo to frontend/public/faction-white-logo.png manually"
fi

# Install dependencies
if npm install; then
    print_success "Frontend dependencies installed"
else
    print_error "Failed to install frontend dependencies"
    exit 1
fi

cd ..

echo ""
echo "========================================="
echo "Setup Complete!"
echo "========================================="
echo ""
echo "To start the application:"
echo ""
echo "Terminal 1 (Backend):"
echo "  cd backend && mvn spring-boot:run"
echo ""
echo "Terminal 2 (Frontend):"
echo "  cd frontend && npm run dev"
echo ""
echo "Then open: http://localhost:3000"
echo ""
echo "Default credentials:"
echo "  Username: admin"
echo "  Password: admin123"
echo ""
echo "========================================="
