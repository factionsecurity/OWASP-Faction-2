# Frontend Architecture & Components

## Overview

The frontend is a React 18 application built with TypeScript and Vite, providing a modern user interface for the FACTION Client Portal. The architecture follows component-based design principles with clear separation of concerns between pages, components, services, utilities, and types.

### Technology Stack
- **React 18**: UI framework with hooks for state management
- **TypeScript**: Static typing for enhanced code quality and IDE support
- **Vite**: Modern build tool with fast development server
- **React Router v6**: Client-side routing
- **Axios**: HTTP client for API communication
- **Playwright**: End-to-end testing framework
- **Lucide React**: Icon library
- **CSS Modules**: Scoped styling

## Project Structure
```
frontend/
├── src/
│   ├── components/          # Reusable UI components
│   │   ├── Badge.tsx        # Status badges
│   │   ├── Button.tsx       # Standard buttons
│   │   ├── Modal.tsx        # Dialog modals
│   │   └── DataTable.tsx    # Data tables with pagination
│   ├── pages/               # Page components (route targets)
│   │   ├── Dashboard.tsx    # Main dashboard
│   │   ├── Users.tsx        # User management
│   │   ├── Assessments.tsx  # Assessment tracking
│   │   └── ReportDesigner.tsx # Report template designer
│   ├── api.ts               # API service layer
│   ├── types.ts             # TypeScript interfaces
│   ├── utils/               # Utility functions
│   │   └── permissions.ts   # Permission checking logic
│   ├── App.tsx              # Main app component with routing
│   └── main.tsx             # Application entry point
├── tests/                   # Playwright E2E tests
│   ├── auth.spec.ts         # Authentication tests
│   └── users.spec.ts        # User management tests
└── package.json
```

## Core Components

### 1. Badge.tsx

A reusable component for displaying status indicators with color coding:

```tsx
// src/components/Badge.tsx
import React from 'react';
import './Badge.css';

interface BadgeProps {
  children: string;
  type?: 'active' | 'inactive' | 'success' | 'danger' | 'warning' | 'primary';
}

const Badge: React.FC<BadgeProps> = ({ children, type = 'primary' }) => {
  const getClassNames = () => {
    let baseClass = 'badge';
    
    switch (type) {
      case 'active':
        return `${baseClass} badge-active`;
      case 'inactive':
        return `${baseClass} badge-inactive`;
      case 'success':
        return `${baseClass} badge-success`;
      case 'danger':
        return `${baseClass} badge-danger`;
      case 'warning':
        return `${baseClass} badge-warning`;
      case 'primary':
        return `${baseClass} badge-primary`;
      default:
        return baseClass;
    }
  };

  return <span className={getClassNames()}>{children}</span>;
};

export default Badge;
```

**CSS Styles** (`Badge.css`):
```css
.badge {
  display: inline-block;
  padding: 0.25rem 0.75rem;
  border-radius: 9999px;
  font-size: 0.875rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.025em;
}

.badge-active {
  background-color: #10b981;
  color: white;
}

.badge-inactive {
  background-color: #6b7280;
  color: white;
}

.badge-success {
  background-color: #10b981;
  color: white;
}

.badge-danger {
  background-color: #ef4444;
  color: white;
}

.badge-warning {
  background-color: #f59e0b;
  color: white;
}

.badge-primary {
  background-color: #3b82f6;
  color: white;
}
```

### 2. Button.tsx

A comprehensive button component with multiple variants:

```tsx
// src/components/Button.tsx
import React from 'react';
import './Button.css';

interface ButtonProps {
  children: React.ReactNode;
  onClick?: () => void;
  type?: 'primary' | 'secondary' | 'danger' | 'warning' | 'success' | 'icon';
  disabled?: boolean;
  className?: string;
  icon?: React.ReactNode;
}

const Button: React.FC<ButtonProps> = ({ 
  children, 
  onClick, 
  type = 'primary', 
  disabled = false, 
  className = '',
  icon 
}) => {
  const getButtonClass = () => {
    let baseClass = 'button';
    
    switch (type) {
      case 'primary':
        return `${baseClass} button-primary ${className}`;
      case 'secondary':
        return `${baseClass} button-secondary ${className}`;
      case 'danger':
        return `${baseClass} button-danger ${className}`;
      case 'warning':
        return `${baseClass} button-warning ${className}`;
      case 'success':
        return `${baseClass} button-success ${className}`;
      case 'icon':
        return `${baseClass} button-icon ${className}`;
      default:
        return `${baseClass} ${className}`;
    }
  };

  return (
    <button 
      className={getButtonClass()} 
      onClick={onClick}
      disabled={disabled}
    >
      {icon && <span className="button-icon">{icon}</span>}
      {children && <span>{children}</span>}
    </button>
  );
};

export default Button;
```

**CSS Styles** (`Button.css`):
```css
.button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0.75rem 1rem;
  border-radius: 0.375rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  border: none;
  outline: none;
}

.button:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
}

.button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.button-icon {
  margin-right: 0.5rem;
}

/* Primary button */
.button-primary {
  background-color: #3b82f6;
  color: white;
}

.button-primary:hover:not(:disabled) {
  background-color: #2563eb;
}

/* Secondary button */
.button-secondary {
  background-color: #1f2937;
  color: white;
  border: 1px solid #374151;
}

.button-secondary:hover:not(:disabled) {
  background-color: #111827;
}

/* Danger button */
.button-danger {
  background-color: #ef4444;
  color: white;
}

.button-danger:hover:not(:disabled) {
  background-color: #dc2626;
}

/* Warning button */
.button-warning {
  background-color: #f59e0b;
  color: white;
}

.button-warning:hover:not(:disabled) {
  background-color: #d97706;
}

/* Success button */
.button-success {
  background-color: #10b981;
  color: white;
}

.button-success:hover:not(:disabled) {
  background-color: #059669;
}

/* Icon button */
.button-icon {
  padding: 0.5rem;
  width: fit-content;
}
```

### 3. Modal.tsx

A reusable modal component for dialogs and forms:

```tsx
// src/components/Modal.tsx
import React from 'react';
import './Modal.css';

interface ModalProps {
  isOpen: boolean;
  onClose: () => void;
  title: string;
  children: React.ReactNode;
  size?: 'sm' | 'md' | 'lg';
}

const Modal: React.FC<ModalProps> = ({ 
  isOpen, 
  onClose, 
  title, 
  children, 
  size = 'md'
}) => {
  if (!isOpen) return null;

  const getSizeClass = () => {
    switch (size) {
      case 'sm':
        return 'modal-sm';
      case 'lg':
        return 'modal-lg';
      default:
        return 'modal-md';
    }
  };

  const handleBackdropClick = (e: React.MouseEvent<HTMLDivElement>) => {
    if (e.target === e.currentTarget) {
      onClose();
    }
  };

  return (
    <div 
      className="modal-backdrop" 
      onClick={handleBackdropClick}
    >
      <div className={`modal-content ${getSizeClass()}`}>
        <div className="modal-header">
          <h2>{title}</h2>
          <button 
            className="btn-close"
            onClick={onClose}
            aria-label="Close modal"
          >
            ×
          </button>
        </div>
        <div className="modal-body">
          {children}
        </div>
      </div>
    </div>
  );
};

export default Modal;
```

**CSS Styles** (`Modal.css`):
```css
.modal-backdrop {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.5);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 1000;
}

.modal-content {
  background-color: #111827;
  border-radius: 0.5rem;
  box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -2px rgba(0, 0, 0, 0.05);
  max-width: 90vw;
  max-height: 90vh;
  overflow-y: auto;
}

.modal-sm {
  width: 30rem;
}

.modal-md {
  width: 48rem;
}

.modal-lg {
  width: 64rem;
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1.5rem;
  border-bottom: 1px solid #374151;
}

.modal-header h2 {
  margin: 0;
  color: white;
  font-size: 1.5rem;
}

.btn-close {
  background: none;
  border: none;
  color: #9ca3af;
  font-size: 2rem;
  cursor: pointer;
  padding: 0;
  line-height: 1;
}

.btn-close:hover {
  color: white;
}

.modal-body {
  padding: 1.5rem;
}
```

### 4. DataTable.tsx

A sophisticated data table component with pagination, search, and filtering:

```tsx
// src/components/DataTable.tsx
import React, { useState, useEffect } from 'react';
import './DataTable.css';

interface Column<T> {
  key: keyof T;
  header: string;
  render?: (value: any, row: T) => React.ReactNode;
  sortable?: boolean;
}

interface DataTableProps<T> {
  data: T[];
  columns: Column<T>[];
  loading?: boolean;
  error?: string;
  onSearch?: (query: string) => void;
  onPageChange?: (page: number) => void;
  onPageSizeChange?: (size: number) => void;
  pagination?: {
    page: number;
    pageSize: number;
    total: number;
    totalPages: number;
  };
}

const DataTable: React.FC<DataTableProps<any>> = ({ 
  data, 
  columns, 
  loading = false, 
  error,
  onSearch,
  onPageChange,
  onPageSizeChange,
  pagination 
}) => {
  const [searchQuery, setSearchQuery] = useState('');

  useEffect(() => {
    if (onSearch) {
      const debounceTimer = setTimeout(() => {
        onSearch(searchQuery);
      }, 300);
      return () => clearTimeout(debounceTimer);
    }
  }, [searchQuery, onSearch]);

  const handlePageSizeChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    if (onPageSizeChange) {
      onPageSizeChange(Number(e.target.value));
    }
  };

  const handlePageChange = (page: number) => {
    if (onPageChange) {
      onPageChange(page);
    }
  };

  // If loading, show skeleton rows
  if (loading) {
    return (
      <div className="data-table-loading">
        {[...Array(5)].map((_, i) => (
          <div key={i} className="skeleton-row">
            {columns.map(col => (
              <div key={col.key as string} className="skeleton-cell" />
            ))}
          </div>
        ))}
      </div>
    );
  }

  // If error, show error message
  if (error) {
    return <div className="data-table-error">{error}</div>;
  }

  // If no data, show empty state
  if (!data || data.length === 0) {
    return (
      <div className="data-table-empty">
        No data available
      </div>
    );
  }

  return (
    <div className="data-table-container">
      {onSearch && (
        <div className="data-table-search">
          <input 
            type="text" 
            placeholder="Search..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="data-table-input"
          />
        </div>
      )}

      <table className="data-table">
        <thead>
          <tr>
            {columns.map((column, index) => (
              <th key={index} className="data-table-header">
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {data.map((row, rowIndex) => (
            <tr key={rowIndex} className="data-table-row">
              {columns.map((column, colIndex) => (
                <td key={colIndex} className="data-table-cell">
                  {column.render 
                    ? column.render(row[column.key], row)
                    : row[column.key]}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>

      {pagination && (
        <div className="data-table-pagination">
          <select 
            value={pagination.pageSize} 
            onChange={handlePageSizeChange}
            className="data-table-select"
          >
            <option value={10}>10 per page</option>
            <option value={25}>25 per page</option>
            <option value={50}>50 per page</option>
            <option value={100}>100 per page</option>
          </select>

          <div className="data-table-pagination-info">
            Page {pagination.page + 1} of {pagination.totalPages}
          </div>

          <div className="data-table-pagination-buttons">
            <button 
              onClick={() => handlePageChange(pagination.page - 1)}
              disabled={pagination.page === 0}
              className="btn-secondary"
            >
              Previous
            </button>
            <button 
              onClick={() => handlePageChange(pagination.page + 1)}
              disabled={pagination.page >= pagination.totalPages - 1}
              className="btn-secondary"
            >
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

export default DataTable;
```

**CSS Styles** (`DataTable.css`):
```css
.data-table-container {
  margin: 1rem 0;
}

.data-table-search {
  margin-bottom: 1rem;
}

.data-table-input {
  padding: 0.5rem;
  border: 1px solid #374151;
  border-radius: 0.375rem;
  background-color: #1f2937;
  color: white;
  width: 100%;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 0.5rem;
}

.data-table-header {
  text-align: left;
  padding: 0.75rem;
  background-color: #1f2937;
  color: white;
  font-weight: 600;
  border-bottom: 1px solid #374151;
}

.data-table-row {
  transition: background-color 0.2s ease;
}

.data-table-row:hover {
  background-color: #1f2937;
}

.data-table-cell {
  padding: 0.75rem;
  border-bottom: 1px solid #374151;
  color: white;
}

.data-table-empty {
  text-align: center;
  padding: 2rem;
  color: #9ca3af;
}

.data-table-loading {
  margin-top: 1rem;
}

.skeleton-row {
  display: flex;
  height: 40px;
}

.skeleton-cell {
  background-color: #374151;
  border-radius: 0.25rem;
  margin-right: 0.5rem;
  flex-grow: 1;
}

.data-table-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1rem;
  background-color: #1f2937;
  border-radius: 0.375rem;
  margin-top: 1rem;
}

.data-table-select {
  padding: 0.5rem;
  border: 1px solid #374151;
  border-radius: 0.375rem;
  background-color: #1f2937;
  color: white;
}

.data-table-pagination-info {
  color: #9ca3af;
}

.data-table-pagination-buttons button {
  margin-left: 0.5rem;
  padding: 0.5rem 1rem;
}

.data-table-error {
  padding: 1rem;
  background-color: #ef4444;
  color: white;
  border-radius: 0.375rem;
  margin-top: 1rem;
}
```

## Pages

### Dashboard.tsx
The main entry point for authenticated users, displaying a dashboard with quick access to key features:
- Overview of active assessments
- Recent activity feed
- Quick navigation to major sections
- System status indicators

### Users.tsx
Comprehensive user management interface:
- Table view of all users with pagination
- Search and filter capabilities
- Create, edit, delete operations via modals
- Permission assignment interface
- Status toggling (active/inactive)

### Assessments.tsx
Advanced assessment tracking system:
- Filterable table of assessments by status, application, type, etc.
- Combined search across multiple fields
- Date range filters for start/end dates
- "Past Due Only" toggle
- "Show only my assessments" toggle (permission-based)
- Export to CSV functionality
- Row click navigation to assessment detail page

### ReportDesigner.tsx
Report template management interface:
- List of available report templates
- Create new templates with rich text editor
- Edit existing templates
- Preview functionality
- Template activation/deactivation
- Permission-based access control

## API Service Layer (api.ts)

The `api.ts` file provides a centralized service layer for all API communications:

```typescript
// src/api.ts
import axios from 'axios';

const API_BASE_URL = '/api/v1';

// Create axios instance with default settings
const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: 10000,
});

// Request interceptor to add JWT token
apiClient.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// Response interceptor to handle errors globally
apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (error.response?.status === 401) {
      // Clear token and redirect to login
      localStorage.removeItem('token');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

// API services
export const usersApi = {
  getAll: (page: number, size: number) => 
    apiClient.get(`/users?page=${page}&size=${size}`),
  getById: (id: string) => apiClient.get(`/users/${id}`),
  create: (user: any) => apiClient.post('/users', user),
  update: (id: string, user: any) => apiClient.put(`/users/${id}`, user),
  delete: (id: string) => apiClient.delete(`/users/${id}`)
};

export const assessmentsApi = {
  search: (params: any) => 
    apiClient.get('/assessments/search', { params }),
  getById: (id: string) => apiClient.get(`/assessments/${id}`),
  create: (assessment: any) => apiClient.post('/assessments', assessment),
  update: (id: string, assessment: any) => 
    apiClient.put(`/assessments/${id}`, assessment)
};

export const assessmentTypesApi = {
  getAll: (page: number, size: number, sort?: string) => 
    apiClient.get(`/assessment-types?page=${page}&size=${size}${sort ? `&sort=${sort}` : ''}`),
  getById: (id: string) => apiClient.get(`/assessment-types/${id}`),
  create: (assessmentType: any) => apiClient.post('/assessment-types', assessmentType),
  update: (id: string, assessmentType: any) => 
    apiClient.put(`/assessment-types/${id}`, assessmentType),
  delete: (id: string) => apiClient.delete(`/assessment-types/${id}`)
};

export const reportTemplatesApi = {
  getAll: () => apiClient.get('/report-templates'),
  getById: (id: string) => apiClient.get(`/report-templates/${id}`),
  create: (template: any) => apiClient.post('/report-templates', template),
  update: (id: string, template: any) => 
    apiClient.put(`/report-templates/${id}`, template),
  delete: (id: string) => apiClient.delete(`/report-templates/${id}`)
};

export default apiClient;
```

## Utility Functions (utils/permissions.ts)

The permissions utility provides a centralized way to check user permissions:

```typescript
// src/utils/permissions.ts
import { useState, useEffect } from 'react';

interface User {
  authorities: string[];
}

export const usePermissions = () => {
  const [user, setUser] = useState<User | null>(null);

  useEffect(() => {
    const storedUser = localStorage.getItem('user');
    if (storedUser) {
      setUser(JSON.parse(storedUser));
    }
  }, []);

  const hasPermission = (permission: string): boolean => {
    return user?.authorities.includes(permission) || false;
  };

  const hasAnyPermission = (permissions: string[]): boolean => {
    if (!user) return false;
    return permissions.some(p => user.authorities.includes(p));
  };

  const hasPermissionPattern = (pattern: RegExp): boolean => {
    if (!user) return false;
    return user.authorities.some(p => pattern.test(p));
  };

  // Permission checks for UI elements
  const permissions = {
    canViewUsers: () => hasAnyPermission(['users:read:team', 'users:read:all']),
    canCreateUsers: () => hasAnyPermission(['users:create:team', 'users:create:all']),
    canEditUsers: () => hasAnyPermission(['users:update:team', 'users:update:all']),
    canDeleteUsers: () => hasAnyPermission(['users:delete:team', 'users:delete:all']),

    canViewOrganizations: () => hasAnyPermission(['organizations:read:team', 'organizations:read:all']),
    canCreateOrganizations: () => hasAnyPermission(['organizations:create:team', 'organizations:create:all']),
    canEditOrganizations: () => hasAnyPermission(['organizations:update:team', 'organizations:update:all']),
    canDeleteOrganizations: () => hasAnyPermission(['organizations:delete:team', 'organizations:delete:all']),

    canViewApplications: () => hasAnyPermission(['applications:read:team', 'applications:read:all']),
    canCreateApplications: () => hasAnyPermission(['applications:create:team', 'applications:create:all']),
    canEditApplications: () => hasAnyPermission(['applications:update:team', 'applications:update:all']),
    canDeleteApplications: () => hasAnyPermission(['applications:delete:team', 'applications:delete:all']),

    canViewAssessments: () => hasAnyPermission(['assessments:read:team', 'assessments:read:all']),
    canCreateAssessments: () => hasAnyPermission(['assessments:create:team', 'assessments:create:all']),
    canEditAssessments: () => hasAnyPermission(['assessments:update:team', 'assessments:update:all']),
    canDeleteAssessments: () => hasAnyPermission(['assessments:delete:team', 'assessments:delete:all']),

    canViewReportTemplates: () => hasAnyPermission(['report-templates:read:team', 'report-templates:read:all']),
    canCreateReportTemplates: () => hasAnyPermission(['report-templates:create:team', 'report-templates:create:all']),
    canEditReportTemplates: () => hasAnyPermission(['report-templates:update:team', 'report-templates:update:all']),
    canDeleteReportTemplates: () => hasAnyPermission(['report-templates:delete:team', 'report-templates:delete:all']),

    isSuperAdmin: () => hasPermission('super_admin'),
  };

  return { user, permissions, hasPermission, hasAnyPermission, hasPermissionPattern };
};
```

## Routing (App.tsx)

The main application routing configuration:

```tsx
// src/App.tsx
import React from 'react';
import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import DashboardLayout from './components/DashboardLayout';
import LoginPage from './pages/Login';
import ProtectedRoute from './components/ProtectedRoute';
import Dashboard from './pages/Dashboard';
import Users from './pages/Users';
import Organizations from './pages/Organizations';
import Applications from './pages/Applications';
import Assessments from './pages/Assessments';
import AssessmentConfig from './pages/AssessmentConfig';
import ReportDesigner from './pages/ReportDesigner';
import Roles from './pages/Roles';
import Teams from './pages/Teams';
import AssessmentDetail from './pages/AssessmentDetail';

function App() {
  return (
    <Router>
      <Routes>
        {/* Public routes */}
        <Route path="/login" element={<LoginPage />} />
        
        {/* Protected routes wrapped in DashboardLayout */}
        <Route path="" element={<DashboardLayout /> }>
          <Route index element={<Dashboard />} />
          <Route 
            path="users" 
            element={
              <ProtectedRoute requiredPermission="canViewUsers">
                <Users />
              </ProtectedRoute>
            }
          />
          <Route 
            path="organizations" 
            element={
              <ProtectedRoute requiredPermission="canViewOrganizations">
                <Organizations />
              </ProtectedRoute>
            }
          />
          <Route 
            path="applications" 
            element={
              <ProtectedRoute requiredPermission="canViewApplications">
                <Applications />
              </ProtectedRoute>
            }
          />
          <Route 
            path="assessments" 
            element={
              <ProtectedRoute requiredPermission="canViewAssessments">
                <Assessments />
              </ProtectedRoute>
            }
          />
          <Route 
            path="assessment-config" 
            element={
              <ProtectedRoute requiredPermission="canCreateAssessments">
                <AssessmentConfig />
              </ProtectedRoute>
            }
          />
          <Route 
            path="report-designer" 
            element={
              <ProtectedRoute requiredPermission="canCreateReportTemplates">
                <ReportDesigner />
              </ProtectedRoute>
            }
          />
          <Route 
            path="roles" 
            element={
              <ProtectedRoute requiredPermission="canViewRoles">
                <Roles />
              </ProtectedRoute>
            }
          />
          <Route 
            path="teams" 
            element={
              <ProtectedRoute requiredPermission="canViewUsers">
                <Teams />
              </ProtectedRoute>
            }
          />
          <Route 
            path="assessments/:id" 
            element={
              <ProtectedRoute requiredPermission="canViewAssessments">
                <AssessmentDetail />
              </ProtectedRoute>
            }
          />
        </Route>
      </Routes>
    </Router>
  );
}

export default App;
```

## Testing Framework (Playwright)

The frontend includes comprehensive end-to-end tests using Playwright:

```typescript
// tests/auth.spec.ts
import { test, expect } from '@playwright/test';

test('should login successfully', async ({ page }) => {
  await page.goto('/login');
  
  // Fill in credentials
  await page.fill('#username', 'admin');
  await page.fill('#password', 'admin123');
  
  // Submit form
  await page.click('button[type="submit"]');
  
  // Verify redirect to dashboard
  await expect(page).toHaveURL('/');
  await expect(page.locator('text=Dashboard')).toBeVisible();
});

test('should show error for invalid credentials', async ({ page }) => {
  await page.goto('/login');
  
  // Fill in invalid credentials
  await page.fill('#username', 'invalid');
  await page.fill('#password', 'wrong');
  
  // Submit form
  await page.click('button[type="submit"]');
  
  // Verify error message is shown
  await expect(page.locator('.error-message')).toHaveText('Invalid username or password');
});
```

```typescript
// tests/users.spec.ts
import { test, expect } from '@playwright/test';

test('should create a new user', async ({ page }) => {
  await page.goto('/login');
  
  // Login as admin
  await page.fill('#username', 'admin');
  await page.fill('#password', 'admin123');
  await page.click('button[type="submit"]');
  
  // Navigate to users page
  await page.goto('/users');
  
  // Click add user button
  await page.click('text=Add User');
  
  // Fill form
  await page.fill('#firstName', 'John');
  await page.fill('#lastName', 'Doe');
  await page.fill('#username', 'johndoe');
  await page.fill('#email', 'john@example.com');
  await page.selectOption('#role', 'Pentester');
  
  // Submit form
  await page.click('text=Create User');
  
  // Verify user appears in table
  await expect(page.locator('table tbody tr').first()).toContainText('John Doe');
});
```

## Design System

### Color Palette (CSS Variables)
- `--primary-bg`: #0a0e1a (Dark background)
- `--secondary-bg`: #111827 (Secondary background)
- `--primary-color`: #3b82f6 (Primary brand blue)
- `--accent-color`: #8b5cf6 (Accent purple)
- `--success-color`: #10b981 (Green for success)
- `--danger-color`: #ef4444 (Red for errors)
- `--warning-color`: #f59e0b (Yellow for warnings)

### Typography
- **Font Family**: Inter, system-ui, sans-serif
- **Headings**: 1.5rem - 2.5rem with font-weight: 700
- **Body Text**: 1rem with line-height: 1.6
- **Monospace**: SF Mono, Consolas, Monaco, monospace

### Spacing System
- `0.25rem` (4px) - Micro spacing
- `0.5rem` (8px) - Small spacing
- `0.75rem` (12px) - Medium spacing
- `1rem` (16px) - Base spacing
- `1.5rem` (24px) - Large spacing
- `2rem` (32px) - Extra large spacing

## Performance Optimization

### Code Splitting
- Route-based lazy loading with React.lazy()
```tsx
const Users = React.lazy(() => import('./pages/Users'));
```

### Image Optimization
- SVG icons for all UI elements
- PNG/JPG images optimized with tools like Squoosh
- Lazy loading for non-critical images

### Bundle Analysis
- Vite's built-in bundle analyzer
- Regular monitoring of bundle size
- Removal of unused dependencies

## Accessibility

The frontend follows WCAG 2.1 guidelines:
- Semantic HTML structure
- Proper ARIA attributes
- Keyboard navigation support
- Focus management in modals
- Sufficient color contrast ratios
- Screen reader compatibility
- Form labels with associated inputs

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

---
This documentation provides a comprehensive overview of the FACTION Client Portal frontend architecture and components. For detailed implementation information on specific pages or features, refer to the individual page documentation files.