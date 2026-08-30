# FACTION Client Portal - System Architecture

## Overview

The FACTION Client Portal is a modern web application designed to manage security assessments, vulnerabilities, and reporting workflows for enterprise clients. The system follows a clean separation of concerns with distinct frontend and backend components communicating via a RESTful API.

### Key Features
- **Assessment Management**: Create, track, and manage security assessments across applications
- **Vulnerability Tracking**: Catalog and prioritize known vulnerabilities with CVSS scoring
- **Report Generation**: Customizable report templates for different assessment types
- **User & Role Management**: Granular permissions system for team collaboration
- **Team & Organization Structure**: Hierarchical organization of users and assets
- **Authentication & Authorization**: JWT-based authentication with fine-grained access control

## Architecture Diagram

```
+------------------+       +-------------------------------------+
|   Client Browser |<----->|           API Gateway (Nginx)       |
+------------------+       +-------------------------------------+
                                 /        |         \
                                /         |          \
                               /          |           \
                      +-----------------+  +-------------+  +------------------+
                      |   Frontend      |  |   Backend   |  |   Database       |
                      | (React/TypeScript)|  | (Spring Boot) |  | (MongoDB)        |
                      | - Dashboard     |  | - Controllers|  | - Users          |
                      | - Assessments   |  | - Services   |  | - Applications   |
                      | - Report Designer|  | - Repositories|  | - Assessments    |
                      | - User Management|  | - Security   |  | - Vulnerabilities|
                      +-----------------+  +-------------+  +------------------+
```

## Frontend Architecture

The frontend is a React application built with TypeScript, using modern web development practices:

### Technology Stack
- **React 18**: Component-based UI library
- **TypeScript**: Static typing for enhanced code quality
- **Vite**: Fast build tool and development server
- **React Router**: Client-side routing
- **Axios**: HTTP client for API communication
- **Tailwind CSS**: Utility-first styling framework
- **Playwright**: End-to-end testing framework
- **ESLint & Prettier**: Code quality and formatting tools

### Component Architecture
The frontend follows a component-based architecture with clear separation between:

1. **UI Components** (reusable, stateless)
   - `Button.tsx`, `Badge.tsx`, `Modal.tsx`, `DataTable.tsx`
   - Styled with Tailwind CSS for consistent design language

2. **Page Components** (route-specific, stateful)
   - `Dashboard.tsx`, `Users.tsx`, `Assessments.tsx`, `ReportDesigner.tsx`
   - Handle data fetching, form submission, and complex interactions

3. **Service Layer** (`api.ts`)
   - Centralized API client with request/response interceptors
   - Type-safe interfaces for all API endpoints
   - Automatic JWT token injection on requests

4. **Utility Functions** (`utils/permissions.ts`)
   - Custom React hook for permission checking
   - Role-based access control logic
   - Integration with authentication state

5. **State Management**
   - Context API for global state (page title, user info)
   - Local component state for form inputs and UI interactions
   - No external state management library (Redux/Zustand) to keep complexity low

### Data Flow
```
User Interaction → Component State → api.ts Service → HTTP Request → Backend API
                                     ↓
                                 HTTP Response ← Backend API
                                     ↓
                           Update Component State → UI Render
```

## Backend Architecture

The backend is a Spring Boot 3.x application with Java 17, following the traditional layered architecture pattern:

### Technology Stack
- **Java 17**: Programming language
- **Spring Boot 3.x**: Web framework with auto-configuration
- **MongoDB**: Document-oriented NoSQL database
- **JWT (JSON Web Tokens)**: Stateless authentication mechanism
- **Lombok**: Reduces boilerplate code
- **MapStruct**: Automatic DTO mapping
- **Maven**: Build tool
- **Swagger/OpenAPI**: API documentation

### Layered Architecture

1. **Controller Layer** (`controller/v1/`)
   - REST endpoints exposed to frontend
   - Request validation and parameter binding
   - Returns DTOs (Data Transfer Objects) to frontend
   - Handles HTTP status codes and error responses

2. **Service Layer** (`service/`)
   - Business logic implementation
   - Coordinates between repositories and controllers
   - Implements application-specific rules and workflows
   - Validates data before persistence
   - Manages transactions (where applicable)

3. **Repository Layer** (`repository/`)
   - Data access objects (DAOs) for MongoDB
   - Provides CRUD operations on domain entities
   - Uses Spring Data MongoDB annotations
   - Implements custom query methods when needed

4. **Model Layer** (`model/`)
   - Domain entities representing database documents
   - Annotated with MongoDB-specific annotations
   - Contains business data and relationships

5. **DTO Layer** (`dto/`)
   - Data Transfer Objects for API communication
   - Separate from domain models to avoid exposing internal structure
   - Includes validation annotations (JSR-303)
   - Generated via MapStruct or manually constructed

6. **Security Layer** (`security/`)
   - JWT token generation and validation
   - Authentication filter for request processing
   - Authorization logic based on user permissions

7. **Exception Handling** (`exception/`)
   - Global exception handler for consistent error responses
   - Custom exceptions for specific error conditions
   - Standardized error response format (JSON)

### Data Flow
```
Frontend Request → API Gateway → Controller → Service → Repository → MongoDB
                                     ↓
                                 Response ← Service ← Repository
                                     ↓
                             HTTP Response ← Controller
                                     ↓
                           Frontend receives data and updates UI
```

## Authentication & Authorization

### Authentication Flow
1. User submits credentials via login form
2. Frontend sends POST request to `/api/v1/auth/login`
3. Backend validates credentials against MongoDB
4. If valid, backend generates JWT token with:
   - Subject (username)
   - User ID
   - List of permissions
   - Expiration time (24 hours)
5. Token is returned in response body
6. Frontend stores token in localStorage
7. On subsequent requests, frontend includes token in Authorization header
8. Backend validates token signature and extracts user information
9. Request proceeds to appropriate controller if valid

### Authorization Flow
1. Each API endpoint has required permissions defined (via Spring Security)
2. JWT token contains list of user permissions
3. `JwtAuthenticationFilter` extracts permissions from token
4. Spring Security checks if user has required permission for requested operation
5. If insufficient permissions, returns 403 Forbidden response
6. Frontend hides UI elements based on user's permissions (via `usePermissions()` hook)

### Permission Hierarchy
```
super_admin → Can access all features
├── users:read:all → View all users
│   └── users:update:all → Edit any user
│       └── users:delete:all → Delete any user
├── assessments:read:team → View assessments in own team
│   └── assessments:create:team → Create assessments for own team
│       └── assessments:update:team → Update assessments in own team
└── report-templates:read:all → View all templates
    └── report-templates:create:all → Create new templates
```

## Data Model

### User Entity
```json
{
  "id": "string",
  "username": "string",
  "email": "string",
  "firstName": "string",
  "lastName": "string",
  "active": "boolean",
  "lastLogin": "datetime",
  "roles": ["string"],
  "permissions": ["string"],
  "teamId": "string",
  "organizationId": "string",
  "createdAt": "datetime",
  "updatedAt": "datetime"
}
```

### Assessment Entity
```json
{
  "id": "string",
  "name": "string",
  "description": "string",
  "status": "planned|in-progress|completed",
  "startDate": "datetime",
  "endDate": "datetime",
  "applicationId": "string",
  "assessmentTypeId": "string",
  "teamId": "string",
  "assessorIds": ["string"],
  "createdBy": "string",
  "createdAt": "datetime",
  "updatedAt": "datetime"
}
```

### ReportTemplate Entity
```json
{
  "id": "string",
  "name": "string",
  "description": "string",
  "content": "string", // JSON or HTML template content
  "type": "assessment|vulnerability",
  "defaultTemplate": "boolean",
  "organizationId": "string",
  "createdAt": "datetime",
  "updatedAt": "datetime"
}
```

## API Design Principles

### RESTful Conventions
- **Resource-based URLs**: `/api/v1/users`, `/api/v1/assessments`
- **HTTP Methods**:
  - `GET` - Retrieve resources
  - `POST` - Create new resource
  - `PUT` - Update entire resource
  - `PATCH` - Partial update (not used in current implementation)
  - `DELETE` - Delete resource
- **Status Codes**:
  - `200 OK` - Successful GET/PUT/PATCH/DELETE
  - `201 Created` - Successful POST
  - `400 Bad Request` - Invalid input data
  - `401 Unauthorized` - Missing or invalid authentication
  - `403 Forbidden` - Insufficient permissions
  - `404 Not Found` - Resource doesn't exist
  - `500 Internal Server Error` - Unexpected server error

### Response Format
All API responses follow a consistent structure:
```json
{
  "data": {}, // Single object or array of objects
  "metadata": { // Pagination, total count, etc.
    "page": 1,
    "pageSize": 10,
    "total": 42,
    "totalPages": 5
  }
}
```

### Error Response Format
```json
{
  "code": "RESOURCE_NOT_FOUND",
  "message": "Assessment not found with id: abc123"
}
```

## Deployment Architecture

### Development Environment
- **Frontend**: Vite development server (port 5173)
- **Backend**: Spring Boot embedded Tomcat (port 8080)
- **Database**: Local MongoDB instance
- **Authentication**: JWT tokens stored in localStorage

### Production Environment
```
+------------------+       +---------------------+
|   Client Browser |<----->|    Nginx Reverse Proxy|
+------------------+       +----------+----------+
                                       |
                                       v
                     +----------------------------------+
                     |        Docker Container (Frontend)  |
                     | - Static files served by Nginx     |
                     +----------------------------------+
                                       |
                                       v
                     +----------------------------------+
                     |      Docker Container (Backend)    |
                     | - Spring Boot application         |
                     | - Exposed on internal port 8080     |
                     +----------------------------------+
                                       |
                                       v
                     +----------------------------------+
                     |       MongoDB Replica Set        |
                     | - Production-grade database      |
                     | - Backed up daily                |
                     +----------------------------------+
```

### CI/CD Pipeline
1. **Code Commit**: Developer pushes to main branch
2. **GitHub Actions Workflow**:
   - Run frontend tests (Playwright)
   - Run backend unit/integration tests
   - Build Docker images for frontend and backend
   - Push images to container registry
3. **Deployment**:
   - Deploy new frontend version to S3/CDN
   - Deploy new backend version to Kubernetes cluster
   - Health checks verify successful deployment
4. **Monitoring**: Prometheus/Grafana dashboards track system health

## Performance Optimization

### Frontend
- Code splitting with React.lazy() for route-based lazy loading
- Image optimization using SVG icons and compressed PNG/JPG
- Bundle analysis to monitor JavaScript bundle size
- Minification and compression of assets
- Caching static assets via service workers

### Backend
- MongoDB indexing on frequently queried fields
- Connection pooling with HikariCP
- Efficient query patterns avoiding N+1 queries
- Redis caching for frequently accessed data (user permissions, role definitions)
- Asynchronous processing for long-running operations

## Security Considerations

### Frontend Security
- HTTPS enforcement for all communications
- Content Security Policy (CSP) headers
- Sanitization of user input before display
- Prevention of XSS attacks through React's built-in escaping
- Secure storage of JWT tokens in localStorage with short expiration

### Backend Security
- Input validation on all API endpoints
- Parameterized queries to prevent NoSQL injection
- Rate limiting on authentication endpoints
- CORS configuration to allow only trusted origins
- HTTPS enforcement (TLS 1.2+)
- Regular dependency scanning for vulnerabilities
- Secure JWT secret key stored as environment variable

### Data Security
- Passwords hashed with BCrypt before storage
- Sensitive data encrypted at rest in MongoDB
- Audit logging of all user actions
- Role-based access control at API level
- Data filtering based on user permissions (never trust client-side checks)

## Testing Strategy

### Frontend Testing
1. **Unit Tests**: Jest for component logic and utility functions
2. **Integration Tests**: React Testing Library for component interactions
3. **End-to-End Tests**: Playwright for full user workflows:
   - Login/logout functionality
   - User creation and management
   - Assessment creation and editing
   - Report template generation
   - Permission-based UI rendering

### Backend Testing
1. **Unit Tests**: JUnit 5 for service layer logic
2. **Integration Tests**: Testcontainers with real MongoDB instance
3. **API Contract Tests**: Spring Boot test slices to verify endpoint behavior
4. **Security Tests**: Verify authentication and authorization requirements

## Monitoring & Logging

### Application Logs
- Structured JSON logging format
- Standardized fields: timestamp, level, message, context
- Log correlation IDs for request tracing
- Environment-specific log levels (INFO in production)

### Metrics Collection
- Prometheus metrics endpoint at `/actuator/prometheus`
- Key metrics:
  - API response times by endpoint
  - Error rates by HTTP status code
  - Database query performance
  - Memory and CPU usage
  - JWT token validation success/failure rate

### Alerting
- Threshold-based alerts for:
  - High error rates (5xx responses)
  - Slow API endpoints (>2s response time)
  - Authentication failures
  - Database connection issues
- Alerts sent via email and Slack

## Future Enhancements

### Frontend Roadmap
1. Add real-time notifications (WebSockets)
2. Implement dark/light mode toggle
3. Add accessibility enhancements (WCAG 2.2 compliance)
4. Integrate with Lighthouse for performance monitoring
5. Add visual regression testing
6. Implement internationalization (i18n) support
7. Add offline capabilities with service workers
8. Improve mobile responsiveness
9. Add drag-and-drop functionality for reordering
10. Implement advanced search filters

### Backend Roadmap
1. Implement audit logging for all data changes
2. Add bulk operations support (bulk create/update/delete)
3. Enhance search capabilities with Elasticsearch integration
4. Add webhook notifications for key events
5. Implement rate limiting and throttling
6. Add caching layer with Redis
7. Support for GraphQL API alongside REST
8. Implement background job processing with RabbitMQ/Kafka
9. Add data export in additional formats (PDF, Excel)
10. Implement multi-tenancy support for enterprise customers

## Maintenance Guidelines

### Code Quality
- Follow existing code patterns and conventions
- Write unit tests for new functionality (target 85%+ coverage)
- Keep services focused on single responsibilities
- Use descriptive variable names
- Document complex logic with comments

### Dependencies
- Regularly update dependencies to patch security vulnerabilities
- Use `mvn dependency-check:check` to identify vulnerabilities
- Pin versions in pom.xml for reproducible builds

### Deployment
- Build production JAR with Maven profile:
  ```bash
  mvn clean package -Pprod
  ```
- Deploy as a Spring Boot application with environment-specific configuration
- Use Docker containers for consistent deployment across environments
- Configure reverse proxy (Nginx) for SSL termination and load balancing

---
This documentation provides a comprehensive overview of the FACTION Client Portal system architecture, covering both frontend and backend components. The architecture follows modern web development best practices with clear separation of concerns, robust security measures, and scalable design principles.