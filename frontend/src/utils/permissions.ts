/**
 * Permission checking utilities
 */

export interface User {
  id: string;
  username: string;
  authorities: string[];
  roles?: string[];
  /**
   * Staff account rather than a customer-side one. Optional because sessions that predate
   * the flag have no copy of it — undefined means "not known yet", not "external", and is
   * backfilled from /auth/me on load (see App.tsx).
   */
  isInternal?: boolean;
}

/**
 * Get current user from localStorage
 */
export const getCurrentUser = (): User | null => {
  try {
    const userStr = localStorage.getItem('user');
    if (!userStr) return null;
    return JSON.parse(userStr);
  } catch {
    return null;
  }
};

/**
 * Check if user has super admin permission
 */
export const isSuperAdmin = (authorities: string[]): boolean => {
  return authorities.includes('super_admin');
};

/**
 * Check if user has any of the specified permissions
 */
export const hasAnyPermission = (
  authorities: string[],
  requiredPermissions: string[]
): boolean => {
  if (isSuperAdmin(authorities)) {
    return true;
  }
  return requiredPermissions.some((permission) =>
    authorities.includes(permission)
  );
};

/**
 * Check if user has permission matching a pattern
 */
export const hasPermissionPattern = (
  authorities: string[],
  pattern: RegExp
): boolean => {
  if (isSuperAdmin(authorities)) {
    return true;
  }
  return authorities.some((auth) => pattern.test(auth));
};

/**
 * Feature-specific permission checks
 */
export const permissions = {
  // Organizations
  canViewOrganizations: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^organizations:read/),

  canCreateOrganizations: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^organizations:create/),

  canEditOrganizations: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^organizations:(edit|read:owned)/),

  canDeleteOrganizations: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^organizations:delete/),

  // Applications
  canViewApplications: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^applications:read/),

  canCreateApplications: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^applications:create/),

  canEditApplications: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^applications:(edit|read:owned)/),

  canDeleteApplications: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^applications:delete/),

  // Assessments
  canViewAssessments: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^assessments:read/),

  canCreateAssessments: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, [
      'assessments:create:all',
      'assessments:create:team',
    ]),

  canEditAssessments: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^assessments:edit/),

  canDeleteAssessments: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^assessments:delete/),

  // Vulnerabilities
  canViewVulnerabilities: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^vulnerabilities:read/),

  canCreateVulnerabilities: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^vulnerabilities:create/),

  canEditVulnerabilities: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^vulnerabilities:edit/),

  canDeleteVulnerabilities: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^vulnerabilities:delete/),

  // Engagement (requires assessment create permissions)
  canViewEngagement: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, [
      'assessments:create:all',
      'assessments:create:team',
    ]),

  // Remediation (requires vulnerability read permissions)
  canViewRemediation: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, [
      'vulnerabilities:read:all',
      'vulnerabilities:read:team',
      'vulnerabilities:retest:org',
      'vulnerabilities:retest:owned',
    ]),

  // Users
  canViewUsers: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, ['users:read:team', 'users:read:all']),

  canCreateUsers: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^users:create/),

  canEditUsers: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^users:edit/),

  canDeleteUsers: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^users:delete/),

  // Teams (uses same permissions as users)
  canViewTeams: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, ['users:read:team', 'users:read:all']),

  canCreateTeams: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^users:create/),

  canEditTeams: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^users:edit/),

  canDeleteTeams: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^users:delete/),

  // Roles
  canViewRoles: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^roles:read/),

  canCreateRoles: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^roles:create/),

  canEditRoles: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^roles:edit/),

  canDeleteRoles: (authorities: string[]): boolean =>
    hasPermissionPattern(authorities, /^roles:delete/),

  // Assessment Config (requires assessment create permissions)
  canViewAssessmentConfig: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, [
      'assessments:create:team',
      'assessments:create:all',
    ]),

  // Assessment Workflow tab — a system-wide settings editor (statuses, SLAs, peer
  // review rules). Every save goes through AssessmentWorkflowConfigController#updateConfig,
  // which requires config:write, and there is no read-only mode worth showing, so the tab
  // is gated on the same permission. The built-in Pentester role doesn't have it: creating
  // assessments (which is what opens Assessment Config) shouldn't confer editing the
  // workflow every assessment runs on.
  canManageAssessmentWorkflow: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, ['config:write']),

  // Default Vulnerabilities
  canViewDefaultVulnerabilities: (_authorities: string[]): boolean =>
    true, // all authenticated users can view

  canCreateDefaultVulnerabilities: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, ['default-vulnerabilities:create']),

  canEditDefaultVulnerabilities: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, ['default-vulnerabilities:edit']),

  canDeleteDefaultVulnerabilities: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, ['default-vulnerabilities:delete']),

  // Vulnerability Categories
  canCreateVulnerabilityCategories: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, ['vulnerability-category:create']),

  canEditVulnerabilityCategories: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, ['vulnerability-category:edit']),

  canDeleteVulnerabilityCategories: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, ['vulnerability-category:delete']),

  // Report Designer (requires assessment create permissions)
  canViewReportDesigner: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, [
      'assessments:create:team',
      'assessments:create:all',
    ]),

  // Organization Config — the page is a super-admin-only editor (all
  // entity-field and region writes require super_admin), with no read-only mode.
  canViewOrgConfig: (authorities: string[]): boolean =>
    isSuperAdmin(authorities),

  // Checklist Templates
  canManageChecklistTemplates: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, ['checklist:create']) || isSuperAdmin(authorities),

  // Campaign management (org-wide lookup tags on assessments)
  canManageCampaigns: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, [
      'campaigns:read:all',
      'campaigns:create:all',
      'campaigns:edit:all',
      'campaigns:delete:all',
    ]) || isSuperAdmin(authorities),

  // Manager dashboard (single org-wide grant, independent of assessment/vuln reads)
  canViewManagerDashboard: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, ['manager_dashboard:read:all']) || isSuperAdmin(authorities),

  // Assigned users management
  canAssignApplicationUsers: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, ['applications:edit:all']) || isSuperAdmin(authorities),

  canAssignOrganizationUsers: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, ['organizations:edit:all']) || isSuperAdmin(authorities),

  // SSO Config
  canManageSsoConfig: (authorities: string[]): boolean =>
    isSuperAdmin(authorities) || hasAnyPermission(authorities, ['sso:config:write']),

  canViewSsoConfig: (authorities: string[]): boolean =>
    isSuperAdmin(authorities) || hasAnyPermission(authorities, ['sso:config:read', 'sso:config:write']),

  // AI Config
  canManageAiConfig: (authorities: string[]): boolean =>
    isSuperAdmin(authorities) || hasAnyPermission(authorities, ['ai:config:write']),

  canViewAiConfig: (authorities: string[]): boolean =>
    isSuperAdmin(authorities) || hasAnyPermission(authorities, ['ai:config:read', 'ai:config:write']),

  // Audit logs
  canViewAuditLogs: (authorities: string[]): boolean =>
    isSuperAdmin(authorities) || hasAnyPermission(authorities, ['audit:logs:read']),

  // App Store extensions. Installing one runs third-party code inside the Faction
  // server, so 'extensions:write' is closer to server access than to ordinary admin.
  canManageExtensions: (authorities: string[]): boolean =>
    isSuperAdmin(authorities) || hasAnyPermission(authorities, ['extensions:write']),

  canViewExtensions: (authorities: string[]): boolean =>
    isSuperAdmin(authorities) || hasAnyPermission(authorities, ['extensions:read', 'extensions:write']),

  // Application ID Config (backend endpoints require super_admin)
  canManageApplicationIdConfig: (authorities: string[]): boolean =>
    isSuperAdmin(authorities),

  canManageSurveys: (authorities: string[]): boolean =>
    isSuperAdmin(authorities) || hasAnyPermission(authorities, ['survey:create', 'survey:edit', 'survey:delete']),

  canCompleteSurveys: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, ['super_admin', 'survey:complete', 'assessments:edit:all', 'assessments:edit:team', 'assessments:edit:self']),

  // API Keys (self-service — any internal user granted an apikeys:*:self permission)
  canManageOwnApiKeys: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, [
      'super_admin',
      'apikeys:create:self',
      'apikeys:read:self',
      'apikeys:delete:self',
    ]),

  // Retests
  canViewRetests: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, [
      'super_admin', 'vulnerabilities:read:all', 'vulnerabilities:read:team', 'vulnerabilities:read:assessment'
    ]),

  // Staff scheduling only. The external retest permissions are deliberately absent: they let an
  // app owner *request* a retest, which is a different act — no assessor or date picking — and
  // including them here put the scheduling form behind a route those users could open but never
  // submit. Requesting is gated by canRequestRetestOnly instead.
  canScheduleRetests: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, [
      'super_admin', 'vulnerabilities:create:all', 'vulnerabilities:create:team', 'vulnerabilities:create:assessment',
    ]),

  // External users (app owners / org users) can only REQUEST a retest — no
  // assessor/date picking. The request lands in the remediation queue as
  // "Retest Requested" for staff to schedule.
  canRequestRetestOnly: (authorities: string[]): boolean =>
    hasAnyPermission(authorities, ['vulnerabilities:retest:org', 'vulnerabilities:retest:owned'])
    && !hasAnyPermission(authorities, [
      'super_admin', 'vulnerabilities:create:all', 'vulnerabilities:create:team', 'vulnerabilities:create:assessment',
    ]),
};

/**
 * Hook to check permissions for current user
 */
export const usePermissions = () => {
  const user = getCurrentUser();
  const authorities = user?.authorities || [];

  return {
    isSuperAdmin: isSuperAdmin(authorities),
    /** Staff, not a customer-side account. Gates internal-only controls; the API re-checks. */
    isInternal: user?.isInternal === true,
    hasPermission: (permission: string) =>
      authorities.includes(permission) || isSuperAdmin(authorities),
    hasAnyPermission: (requiredPermissions: string[]) =>
      hasAnyPermission(authorities, requiredPermissions),
    hasPermissionPattern: (pattern: RegExp) =>
      hasPermissionPattern(authorities, pattern),
    permissions: Object.fromEntries(
      Object.entries(permissions).map(([key, fn]) => [key, fn(authorities)])
    ) as Record<keyof typeof permissions, boolean>,
  };
};
