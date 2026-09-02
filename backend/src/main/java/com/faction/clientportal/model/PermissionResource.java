package com.faction.clientportal.model;

public enum PermissionResource {
    APPLICATIONS("Applications", "Application management permissions"),
    ASSESSMENTS("Assessments", "Assessment management permissions"),
    USERS("Users", "User management permissions"),
    ORGANIZATIONS("Organizations", "Organization management permissions"),
    VULNERABILITIES("Vulnerabilities", "Vulnerability management permissions"),
    REPORTING("Reporting", "Reporting and document generation permissions"),
    ROLES("Roles", "Role management permissions"),
    REPORT_TEMPLATES("Report Templates", "Report template management permissions"),
    VULNERABILITY_CATEGORIES("Vulnerability Categories", "Vulnerability category management permissions"),
    DEFAULT_VULNERABILITIES("Default Vulnerabilities", "Default vulnerability template management permissions"),
    CHECKLIST_TEMPLATES("Checklist Templates", "Checklist template management permissions"),
    SURVEY_TEMPLATES("Survey Templates", "Survey template management permissions"),
    CONTENT_TEMPLATES("Content Templates", "Rich text content template management permissions"),
    PEER_REVIEW("Peer Review", "Peer review management permissions"),
    API_KEYS("API Keys", "API key management permissions"),
    SYSTEM_CONFIG("System Config", "System configuration permissions"),
    CAMPAIGNS("Campaigns", "Campaign management permissions"),
    MANAGER_DASHBOARD("Manager Dashboard", "Manager dashboard read access");

    private final String displayName;
    private final String description;

    PermissionResource(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
