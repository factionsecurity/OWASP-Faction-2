package com.faction.clientportal.model;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public enum Permission {
    // User Permissions
    USERS_READ_TEAM("users:read:team", "Read team users", PermissionResource.USERS),
    USERS_EDIT_TEAM("users:edit:team", "Edit team users", PermissionResource.USERS),
    USERS_CREATE_TEAM("users:create:team", "Create team users", PermissionResource.USERS),
    USERS_DELETE_TEAM("users:delete:team", "Delete team users", PermissionResource.USERS),
    USERS_READ_ALL("users:read:all", "Read all users", PermissionResource.USERS),
    USERS_EDIT_ALL("users:edit:all", "Edit all users", PermissionResource.USERS),
    USERS_CREATE_ALL("users:create:all", "Create users", PermissionResource.USERS),
    USERS_DELETE_ALL("users:delete:all", "Delete users", PermissionResource.USERS),

    // Role Permissions
    ROLES_READ_ALL("roles:read:all", "Read all roles", PermissionResource.ROLES),
    ROLES_EDIT_ALL("roles:edit:all", "Edit all roles", PermissionResource.ROLES),
    ROLES_CREATE_ALL("roles:create:all", "Create roles", PermissionResource.ROLES),
    ROLES_DELETE_ALL("roles:delete:all", "Delete roles", PermissionResource.ROLES),

    // Organization Permissions
    ORGANIZATIONS_READ_ALL("organizations:read:all", "Read all organizations", PermissionResource.ORGANIZATIONS),
    ORGANIZATIONS_CREATE_ALL("organizations:create:all", "Create organizations", PermissionResource.ORGANIZATIONS),
    ORGANIZATIONS_EDIT_ALL("organizations:edit:all", "Edit all organizations", PermissionResource.ORGANIZATIONS),
    ORGANIZATIONS_DELETE_ALL("organizations:delete:all", "Delete organizations", PermissionResource.ORGANIZATIONS),

    // Application Permissions
    APPLICATIONS_READ_ALL("applications:read:all", "Read all applications", PermissionResource.APPLICATIONS),
    APPLICATIONS_CREATE_ALL("applications:create:all", "Create applications", PermissionResource.APPLICATIONS),
    APPLICATIONS_EDIT_ALL("applications:edit:all", "Edit all applications", PermissionResource.APPLICATIONS),
    APPLICATIONS_DELETE_ALL("applications:delete:all", "Delete applications", PermissionResource.APPLICATIONS),

    // Assessment Permissions
    ASSESSMENTS_READ_TEAM("assessments:read:team", "Read team assessments", PermissionResource.ASSESSMENTS),
    ASSESSMENTS_EDIT_TEAM("assessments:edit:team", "Edit team assessments", PermissionResource.ASSESSMENTS),
    ASSESSMENTS_CREATE_TEAM("assessments:create:team", "Create team assessments", PermissionResource.ASSESSMENTS),
    ASSESSMENTS_DELETE_TEAM("assessments:delete:team", "Delete team assessments", PermissionResource.ASSESSMENTS),
    ASSESSMENTS_READ_ALL("assessments:read:all", "Read all assessments", PermissionResource.ASSESSMENTS),
    ASSESSMENTS_EDIT_ALL("assessments:edit:all", "Edit all assessments", PermissionResource.ASSESSMENTS),
    ASSESSMENTS_CREATE_ALL("assessments:create:all", "Create assessments", PermissionResource.ASSESSMENTS),
    ASSESSMENTS_DELETE_ALL("assessments:delete:all", "Delete all assessments", PermissionResource.ASSESSMENTS),
    ASSESSMENTS_EDIT_SELF("assessments:edit:self", "Edit own assessments", PermissionResource.ASSESSMENTS),
    ASSESSMENTS_READ_ASSIGNED("assessments:read:assigned", "Read assigned assessments", PermissionResource.ASSESSMENTS),
    ASSESSMENTS_EDIT_ASSIGNED("assessments:edit:assigned", "Edit assigned assessments", PermissionResource.ASSESSMENTS),
    ASSESSMENTS_READ_OWNED("assessments:read:owned", "Read assessments of owned applications", PermissionResource.ASSESSMENTS),


    // Vulnerability Permissions
    VULNERABILITIES_READ_ASSESSMENT("vulnerabilities:read:assessment", "Read assessment vulnerabilities", PermissionResource.VULNERABILITIES),
    VULNERABILITIES_EDIT_ASSESSMENT("vulnerabilities:edit:assessment", "Edit assessment vulnerabilities", PermissionResource.VULNERABILITIES),
    VULNERABILITIES_CREATE_ASSESSMENT("vulnerabilities:create:assessment", "Create assessment vulnerabilities", PermissionResource.VULNERABILITIES),
    VULNERABILITIES_DELETE_ASSESSMENT("vulnerabilities:delete:assessment", "Delete assessment vulnerabilities", PermissionResource.VULNERABILITIES),
    VULNERABILITIES_READ_TEAM("vulnerabilities:read:team", "Read team vulnerabilities", PermissionResource.VULNERABILITIES),
    VULNERABILITIES_EDIT_TEAM("vulnerabilities:edit:team", "Edit team vulnerabilities", PermissionResource.VULNERABILITIES),
    VULNERABILITIES_CREATE_TEAM("vulnerabilities:create:team", "Create team vulnerabilities", PermissionResource.VULNERABILITIES),
    VULNERABILITIES_DELETE_TEAM("vulnerabilities:delete:team", "Delete team vulnerabilities", PermissionResource.VULNERABILITIES),
    VULNERABILITIES_READ_ALL("vulnerabilities:read:all", "Read all vulnerabilities", PermissionResource.VULNERABILITIES),
    VULNERABILITIES_EDIT_ALL("vulnerabilities:edit:all", "Edit all vulnerabilities", PermissionResource.VULNERABILITIES),
    VULNERABILITIES_CREATE_ALL("vulnerabilities:create:all", "Create vulnerabilities", PermissionResource.VULNERABILITIES),
    VULNERABILITIES_DELETE_ALL("vulnerabilities:delete:all", "Delete all vulnerabilities", PermissionResource.VULNERABILITIES),

    // Reporting Permissions
    REPORTING_CREATE("reporting:create", "Create reports", PermissionResource.REPORTING),
    REPORTING_DOWNLOAD("reporting:download", "Download reports", PermissionResource.REPORTING),
    REPORTING_DOWNLOAD_OWNED("reporting:download:owned", "Download reports for owned applications", PermissionResource.REPORTING),

    // Report Template Permissions
    REPORT_TEMPLATES_CREATE_ALL("report_templates:create:all", "Create report templates", PermissionResource.REPORT_TEMPLATES),
    REPORT_TEMPLATES_READ_ALL("report_templates:read:all", "Read all report templates", PermissionResource.REPORT_TEMPLATES),
    REPORT_TEMPLATES_EDIT_ALL("report_templates:edit:all", "Edit all report templates", PermissionResource.REPORT_TEMPLATES),
    REPORT_TEMPLATES_DELETE_ALL("report_templates:delete:all", "Delete report templates", PermissionResource.REPORT_TEMPLATES),

    // Vulnerability Category Permissions
    VULNERABILITY_CATEGORIES_CREATE("vulnerability-category:create", "Create vulnerability categories", PermissionResource.VULNERABILITY_CATEGORIES),
    VULNERABILITY_CATEGORIES_EDIT("vulnerability-category:edit", "Edit vulnerability categories", PermissionResource.VULNERABILITY_CATEGORIES),
    VULNERABILITY_CATEGORIES_DELETE("vulnerability-category:delete", "Delete vulnerability categories", PermissionResource.VULNERABILITY_CATEGORIES),

    // Default Vulnerability Permissions (reads are open to all authenticated users)
    DEFAULT_VULNERABILITIES_CREATE("default-vulnerabilities:create", "Create default vulnerabilities", PermissionResource.DEFAULT_VULNERABILITIES),
    DEFAULT_VULNERABILITIES_EDIT("default-vulnerabilities:edit", "Edit default vulnerabilities", PermissionResource.DEFAULT_VULNERABILITIES),
    DEFAULT_VULNERABILITIES_DELETE("default-vulnerabilities:delete", "Delete default vulnerabilities", PermissionResource.DEFAULT_VULNERABILITIES),

    // Checklist Template Permissions
    CHECKLIST_TEMPLATES_CREATE("checklist:create", "Create and manage checklist templates", PermissionResource.CHECKLIST_TEMPLATES),
    CHECKLIST_TEMPLATES_EDIT("checklist:edit", "Edit checklist templates", PermissionResource.CHECKLIST_TEMPLATES),
    CHECKLIST_TEMPLATES_DELETE("checklist:delete", "Delete checklist templates", PermissionResource.CHECKLIST_TEMPLATES),

    // Survey Template Permissions
    SURVEY_TEMPLATES_CREATE("survey:create", "Create and manage survey templates", PermissionResource.SURVEY_TEMPLATES),
    SURVEY_TEMPLATES_EDIT("survey:edit", "Edit survey templates", PermissionResource.SURVEY_TEMPLATES),
    SURVEY_TEMPLATES_DELETE("survey:delete", "Delete survey templates", PermissionResource.SURVEY_TEMPLATES),

    // Survey completion (app owner)
    SURVEYS_COMPLETE("survey:complete", "Complete assigned surveys", PermissionResource.SURVEY_TEMPLATES),

    // Owned-scope Permissions
    APPLICATIONS_READ_OWNED("applications:read:owned", "Read assigned applications", PermissionResource.APPLICATIONS),
    APPLICATIONS_CREATE_OWNED("applications:create:owned", "Create owned applications", PermissionResource.APPLICATIONS),
    ORGANIZATIONS_READ_OWNED("organizations:read:owned", "Read assigned organizations", PermissionResource.ORGANIZATIONS),

    // Peer Review Permissions

    // System Config Permissions
    CONFIG_WRITE("config:write", "Edit system configuration", PermissionResource.SYSTEM_CONFIG),
    SSO_CONFIG_READ("sso:config:read", "Read SSO configuration", PermissionResource.SYSTEM_CONFIG),
    SSO_CONFIG_WRITE("sso:config:write", "Write SSO configuration", PermissionResource.SYSTEM_CONFIG),
    AI_CONFIG_READ("ai:config:read", "Read AI provider configuration", PermissionResource.SYSTEM_CONFIG),
    AI_CONFIG_WRITE("ai:config:write", "Write AI provider configuration", PermissionResource.SYSTEM_CONFIG),
    AUDIT_LOGS_READ("audit:logs:read", "Read audit logs", PermissionResource.SYSTEM_CONFIG),
    EXTENSIONS_READ("extensions:read", "Read installed extensions", PermissionResource.SYSTEM_CONFIG),
    EXTENSIONS_WRITE("extensions:write", "Install and configure extensions", PermissionResource.SYSTEM_CONFIG),

    // Org-scoped permissions for external (portal) users
    ORGANIZATIONS_READ_ORG("organizations:read:org", "Read own organization", PermissionResource.ORGANIZATIONS),
    APPLICATIONS_READ_ORG("applications:read:org", "Read organization applications", PermissionResource.APPLICATIONS),
    APPLICATIONS_CREATE_ORG("applications:create:org", "Create organization applications", PermissionResource.APPLICATIONS),
    APPLICATIONS_EDIT_ORG("applications:edit:org", "Edit organization applications", PermissionResource.APPLICATIONS),
    ASSESSMENTS_READ_ORG("assessments:read:org", "Read organization assessments", PermissionResource.ASSESSMENTS),
    VULNERABILITIES_READ_ORG("vulnerabilities:read:org", "Read organization vulnerabilities", PermissionResource.VULNERABILITIES),
    VULNERABILITIES_RETEST_ORG("vulnerabilities:retest:org", "Request vulnerability retests", PermissionResource.VULNERABILITIES),
    VULNERABILITIES_COMMENT_ORG("vulnerabilities:comment:org", "Comment on organization vulnerabilities", PermissionResource.VULNERABILITIES),

    // Peer Review Permissions. ":read:team"/":edit:team" ARE enforced: PeerReviewController gates on
    // them alongside the ":all" variants, and PeerReviewService#sharesTeam filters the queue and every
    // single-review lookup to reviews whose assessment shares a team with the caller. Note that an
    // assessment's teams are derived there from its assessors' teamIds, not from the assessment's own
    // teamId column that AccessScopeService filters on — the two can disagree.
    // ":create:assessment" is enforced too: the submit endpoint accepts it, and the service then
    // requires the caller to be one of that assessment's own assessors.
    PEERREVIEW_READ_ALL("peerreview:read:all", "Read all peer reviews", PermissionResource.PEER_REVIEW),
    PEERREVIEW_READ_TEAM("peerreview:read:team", "Read team peer reviews (not yet enforced)", PermissionResource.PEER_REVIEW),
    PEERREVIEW_EDIT_ALL("peerreview:edit:all", "Edit all peer reviews", PermissionResource.PEER_REVIEW),
    PEERREVIEW_EDIT_TEAM("peerreview:edit:team", "Edit team peer reviews (not yet enforced)", PermissionResource.PEER_REVIEW),
    PEERREVIEW_CREATE_ALL("peerreview:create:all", "Create peer reviews for any assessment", PermissionResource.PEER_REVIEW),
    PEERREVIEW_CREATE_ASSESSMENT("peerreview:create:assessment", "Create peer reviews for an assessment (not yet enforced)", PermissionResource.PEER_REVIEW),

    // API Key Permissions.
    // Scope convention: ":self" narrows to your own keys; ":system" narrows to system (ownerless,
    // service-account) keys. There are deliberately NO unscoped ("all") apikeys permissions: an
    // unscoped grant would imply a cross-user management surface that does not exist (every user-key
    // endpoint is self-scoped) and would be a second path to privilege escalation. Reintroduce them
    // only alongside a real "manage another user's keys" surface, defined precisely at that time.
    //
    // Minting a system key is an unbounded, ownerless permission grant (it can carry any authority),
    // so system CREATE and EDIT are gated at super_admin only — the same bar this codebase already
    // applies to role management. Only the non-escalating verbs (read, delete/revoke) are delegable
    // via ":system". This keeps the invariant: an API key can never confer a permission its creator
    // does not already hold. (No ":all" string, so the loose contains(":all") check in
    // UserService.hasAllScopeOrSuperAdmin is never triggered.)
    APIKEYS_CREATE_SELF("apikeys:create:self", "Create your own API keys", PermissionResource.API_KEYS),
    APIKEYS_READ_SELF("apikeys:read:self", "Read your own API keys", PermissionResource.API_KEYS),
    APIKEYS_DELETE_SELF("apikeys:delete:self", "Revoke your own API keys", PermissionResource.API_KEYS),
    APIKEYS_READ_SYSTEM("apikeys:read:system", "Read system (service-account) API keys", PermissionResource.API_KEYS),
    APIKEYS_DELETE_SYSTEM("apikeys:delete:system", "Revoke system (service-account) API keys", PermissionResource.API_KEYS),

    // Owned-scope permissions for external users assigned to specific applications or organizations
    VULNERABILITIES_READ_OWNED("vulnerabilities:read:owned", "Read vulnerabilities of owned applications", PermissionResource.VULNERABILITIES),
    VULNERABILITIES_COMMENT_OWNED("vulnerabilities:comment:owned", "Comment on vulnerabilities of owned applications", PermissionResource.VULNERABILITIES),
    VULNERABILITIES_RETEST_OWNED("vulnerabilities:retest:owned", "Schedule retests for owned applications", PermissionResource.VULNERABILITIES),

    // Campaign Permissions. Campaigns are org-wide lookup tags (no team/owned scoping),
    // so only ":all" variants exist — same shape as the legacy Manager/Admin-only gate.
    CAMPAIGNS_READ_ALL("campaigns:read:all", "Read all campaigns", PermissionResource.CAMPAIGNS),
    CAMPAIGNS_CREATE_ALL("campaigns:create:all", "Create campaigns", PermissionResource.CAMPAIGNS),
    CAMPAIGNS_EDIT_ALL("campaigns:edit:all", "Edit campaigns", PermissionResource.CAMPAIGNS),
    CAMPAIGNS_DELETE_ALL("campaigns:delete:all", "Delete campaigns", PermissionResource.CAMPAIGNS),

    // Manager Dashboard Permission. A single org-wide grant, deliberately independent of
    // assessments:read:*/vulnerabilities:read:* — holding those does NOT expose the dashboard,
    // and holding this alone exposes every assessment/vulnerability through the dashboard.
    MANAGER_DASHBOARD_READ_ALL("manager_dashboard:read:all", "Read manager dashboard", PermissionResource.MANAGER_DASHBOARD);

    private final String permission;
    private final String description;
    private final PermissionResource resource;

    Permission(String permission, String description, PermissionResource resource) {
        this.permission = permission;
        this.description = description;
        this.resource = resource;
    }

    public String getPermission() {
        return permission;
    }

    public String getDescription() {
        return description;
    }

    public PermissionResource getResource() {
        return resource;
    }

    public static List<Permission> getByResource(PermissionResource resource) {
        return Arrays.stream(values())
                .filter(p -> p.getResource() == resource)
                .collect(Collectors.toList());
    }

    public static Permission fromString(String permission) {
        return Arrays.stream(values())
                .filter(p -> p.getPermission().equals(permission))
                .findFirst()
                .orElse(null);
    }

    // ── Read/write classification (used by ApiKeyScope.READ_ONLY resolution) ─

    /**
     * Action tokens (the middle segment of {@code resource:action:scope}) considered non-mutating.
     * {@code view} is included because some endpoint gates use it even though this enum currently
     * doesn't. Anything not listed here — including future, as-yet-unknown actions — is treated as
     * mutating (deny-by-default), so a new permission can never silently leak into read-only keys.
     */
    private static final Set<String> READ_ACTIONS = Set.of("read", "view", "download");

    /**
     * Whether a permission string is a pure read (non-mutating) action. {@code super_admin} and any
     * string without an action segment are NOT read actions.
     */
    public static boolean isReadAction(String permission) {
        if (permission == null) {
            return false;
        }
        String[] parts = permission.split(":");
        return parts.length >= 2 && READ_ACTIONS.contains(parts[1]);
    }

    /**
     * Every read-action permission string this enum knows — the universe a {@code super_admin}
     * owner expands to for a read-only key. Only as complete as this enum: gate strings missing
     * from the registry are invisible here.
     */
    public static List<String> allReadPermissions() {
        return Arrays.stream(values())
                .map(Permission::getPermission)
                .filter(Permission::isReadAction)
                .collect(Collectors.toList());
    }
}
