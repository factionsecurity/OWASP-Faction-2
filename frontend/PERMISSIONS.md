# Permission-Based UI System

## Overview

The application now implements a comprehensive permission-based UI system that controls which features users can access based on their assigned roles and authorities.

## Architecture

### 1. Permission Utility (`/src/utils/permissions.ts`)

Central permission checking utility with:
- **Permission Patterns**: Helper functions to check permission patterns
- **Feature Permissions**: Specific checks for each feature (view, create, edit, delete)
- **usePermissions Hook**: React hook for component-level permission checks

### 2. Protected Route Component (`/src/components/ProtectedRoute.tsx`)

Wrapper component that:
- Checks if user has required permission to access a route
- Redirects to dashboard if permission is missing
- Prevents unauthorized access at the route level

### 3. Navigation Filtering (`/src/components/DashboardLayout.tsx`)

Navigation menu items are filtered based on permissions:
- Top-level menu items hidden if user lacks access
- Administration submenu items filtered individually
- Administration parent only shown if user has access to at least one sub-item

## Permission Requirements by Feature

### Super Admin
- Has access to **all features** regardless of other permissions
- Authority: `super_admin`

### Top-Level Features

| Feature | Required Permission Pattern |
|---------|----------------------------|
| **Dashboard** | Always visible (no permission required) |
| **Organizations** | `organizations:read:*` |
| **Applications** | `applications:read:*` |
| **Assessments** | `assessments:read:*` |
| **Vulnerabilities** | `vulnerabilities:read:*` |
| **Engagement** | `assessments:create:all` OR `assessments:create:team` |
| **Remediation** | `vulnerabilities:read:all` OR `vulnerabilities:read:team` |

### Administration Submenu

| Feature | Required Permission |
|---------|-------------------|
| **Users** | `users:read:team` OR `users:read:all` |
| **Teams** | `users:read:team` OR `users:read:all` |
| **Roles** | `roles:read:*` |
| **Assessment Config** | `assessments:create:team` OR `assessments:create:all` |
| **Report Designer** | `assessments:create:team` OR `assessments:create:all` |

## Implementation Details

### Permission Format

Permissions follow the pattern: `resource:action:scope`

Examples:
- `organizations:read:all` - Read all organizations
- `organizations:read:team` - Read team organizations only
- `assessments:create:team` - Create assessments for team
- `super_admin` - Full access to everything

### Permission Checking Logic

1. **Super Admin Override**: If user has `super_admin` authority, all checks return `true`
2. **Pattern Matching**: Uses regex to match permission patterns (e.g., `/^organizations:read/`)
3. **Exact Matching**: Some checks require exact permission strings
4. **Multiple Options**: Some features accept multiple permission types (OR logic)

### Using Permissions in Code

#### Route Protection

```tsx
<Route
  path="/organizations"
  element={
    isAuthenticated ? (
      <DashboardLayout>
        <ProtectedRoute requiredPermission="canViewOrganizations">
          <Organizations />
        </ProtectedRoute>
      </DashboardLayout>
    ) : (
      <Navigate to="/login" replace />
    )
  }
/>
```

#### Component-Level Permission Checks

```tsx
import { usePermissions } from '../utils/permissions';

function MyComponent() {
  const { permissions } = usePermissions();

  return (
    <div>
      {permissions.canCreateOrganizations && (
        <Button onClick={handleCreate}>Create Organization</Button>
      )}

      {permissions.canEditOrganizations && (
        <Button onClick={handleEdit}>Edit</Button>
      )}

      {permissions.canDeleteOrganizations && (
        <Button onClick={handleDelete}>Delete</Button>
      )}
    </div>
  );
}
```

#### Custom Permission Checks

```tsx
import { usePermissions } from '../utils/permissions';

function MyComponent() {
  const { hasPermission, hasAnyPermission, hasPermissionPattern } = usePermissions();

  // Check specific permission
  if (hasPermission('organizations:create:all')) {
    // ...
  }

  // Check for any of multiple permissions
  if (hasAnyPermission(['users:read:team', 'users:read:all'])) {
    // ...
  }

  // Check permission pattern
  if (hasPermissionPattern(/^assessments:create/)) {
    // ...
  }
}
```

## Security Considerations

### Defense in Depth

The system implements multiple layers of protection:

1. **Navigation Filtering**: Hides menu items user can't access
2. **Route Protection**: Blocks access at route level with redirects
3. **API Authorization**: Backend enforces permissions (not just UI hiding)
4. **Component-Level**: Individual features can check permissions for actions

### User Experience

- Users only see features they can access
- Attempting to access restricted routes redirects to dashboard
- Clear and consistent permission checking throughout the app
- No confusing "Access Denied" pages - smooth redirects instead

## Testing Permission Scenarios

### Test User Roles

1. **Super Admin**
   - Should see all features
   - Authority: `super_admin`

2. **Organization Manager**
   - Can view/edit organizations
   - Authorities: `organizations:read:all`, `organizations:update:all`

3. **Team Lead**
   - Can create assessments for team
   - Can view team users
   - Authorities: `assessments:create:team`, `users:read:team`

4. **Read-Only User**
   - Can view but not modify
   - Authorities: `organizations:read:team`, `applications:read:team`

### Verification Steps

1. Log in as test user
2. Check navigation menu - only permitted items should appear
3. Try accessing restricted routes directly - should redirect to dashboard
4. Check buttons/actions within pages - only permitted actions should show

## Adding New Permissions

### 1. Add Permission Check to Utility

Edit `/src/utils/permissions.ts`:

```typescript
export const permissions = {
  // ... existing permissions

  // New feature
  canViewNewFeature: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^newfeature:read/),

  canCreateNewFeature: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^newfeature:create/),
};
```

### 2. Add Menu Item Permission Check

Edit `/src/components/DashboardLayout.tsx`:

```typescript
case 'new feature':
  return authorities.some((auth: string) =>
    auth.match(/^newfeature:read/)
  );
```

### 3. Protect Route

Edit `/src/App.tsx`:

```tsx
<Route
  path="/new-feature"
  element={
    isAuthenticated ? (
      <DashboardLayout>
        <ProtectedRoute requiredPermission="canViewNewFeature">
          <NewFeature />
        </ProtectedRoute>
      </DashboardLayout>
    ) : (
      <Navigate to="/login" replace />
    )
  }
/>
```

## Troubleshooting

### User Can't See Expected Features

1. Check user's authorities in localStorage: `localStorage.getItem('user')`
2. Verify authorities array contains expected permissions
3. Check permission pattern matches in `permissions.ts`
4. Verify menu item name matches in `DashboardLayout.tsx` switch statement

### Navigation Menu Not Updating

1. Clear browser cache
2. Log out and log back in to refresh user data
3. Check browser console for errors

### Routes Still Accessible Despite No Permission

1. Verify ProtectedRoute is wrapping the component
2. Check permission name matches between route and permissions.ts
3. Ensure backend also enforces the same permissions
