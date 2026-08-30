import { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { getCurrentUser, permissions } from '../utils/permissions';

interface ProtectedRouteProps {
  children: ReactNode;
  requiredPermission: keyof typeof permissions;
}

/**
 * ProtectedRoute component - Redirects to dashboard if user lacks required permission
 */
export default function ProtectedRoute({
  children,
  requiredPermission,
}: ProtectedRouteProps) {
  const user = getCurrentUser();
  const authorities = user?.authorities || [];

  // Check if user has the required permission
  const hasPermission = permissions[requiredPermission](authorities);

  if (!hasPermission) {
    // Redirect to dashboard if no permission
    return <Navigate to="/dashboard" replace />;
  }

  return <>{children}</>;
}
