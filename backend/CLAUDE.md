# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development Environment

This project uses:
- **Java**: openjdk-17.0.2
- **Maven**: 3.9.9

Tool versions are managed via `.tool-versions` (asdf compatible).

## Project Overview

This is a Spring Boot REST API application with TimescaleDB (PostgreSQL), JWT authentication, and fine-grained permissions.

### Features
- JWT-based authentication
- Role-based access control with fine-grained permissions
- TimescaleDB (PostgreSQL) with JSONB for data persistence
- OpenAPI/Swagger documentation
- Comprehensive test coverage with Testcontainers

## Build and Run

### Prerequisites
- Java 17
- Maven 3.9.9
- Docker (for TimescaleDB)

### Start TimescaleDB
```bash
docker compose up -d
```

### Build Project
```bash
mvn clean install
```

### Run Tests
```bash
mvn test
```

### Run Application
```bash
mvn spring-boot:run
```

The application will start on http://localhost:8080

## API Documentation

Once the application is running, access:
- Swagger UI: http://localhost:8080/swagger-ui/index.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

## Default Users

The application creates two default users on first startup:

1. **Admin User**
   - Username: `admin`
   - Password: `admin123`
   - Role: SuperAdmin
   - Permissions: `super_admin`

2. **Pentester User**
   - Username: `pentest`
   - Password: `pentest123`
   - Role: Pentester
   - Permissions: 19 specific pentesting-related permissions

## Testing the API

### Login as Admin
```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

### Login as Pentester
```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"pentest","password":"pentest123"}'
```

## Architecture

### Package Structure
```
com.faction.clientportal
├── config/              # Configuration classes
├── model/              # Domain models (User, Role, Organization)
├── repository/         # JPA repositories
├── service/           # Business logic
├── controller/        # REST endpoints
├── dto/              # Data Transfer Objects
├── security/         # JWT and security components
└── exception/        # Exception handling
```

### Key Components
- **SecurityConfig**: Spring Security configuration with JWT filter
- **JwtTokenProvider**: JWT token generation and validation
- **BootstrapService**: Initializes default users and roles
- **AuthService**: Handles authentication logic
- **GlobalExceptionHandler**: Centralized error handling
