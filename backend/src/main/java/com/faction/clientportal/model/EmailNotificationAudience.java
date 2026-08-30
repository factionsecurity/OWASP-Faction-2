package com.faction.clientportal.model;

/**
 * The groups of people an event email can be addressed to.
 *
 * <p>These are audiences, not users: a stakeholder or an app owner is an email address
 * recorded on an application or an assessment, and usually has no login at all. That is
 * why this is an admin-level routing config rather than a per-user preference — the
 * people being emailed cannot set a preference of their own.
 * {@link NotificationCategory} remains the per-user opt-out for people who <em>do</em>
 * have accounts.
 */
public enum EmailNotificationAudience {

    /** Users assigned to the assessment (assessors, engagement and remediation managers). */
    ASSESSORS("Assessors"),

    /** Stakeholders recorded on the assessment and on its application. */
    STAKEHOLDERS("Stakeholders"),

    /** The owner recorded on the application. */
    APP_OWNER("App owner"),

    /** Users @mentioned in the item's comment thread. */
    MENTIONED_USERS("Mentioned users"),

    /**
     * The user assigned as the finding's remediation owner.
     *
     * <p>Not a configurable switch, which is why no event lists it in its
     * {@link EmailNotificationEvent#audiences()}: assigning someone as the owner <em>is</em> the
     * opt-in, and an SLA breach on a finding nobody was told about is exactly the failure this
     * whole feature exists to prevent. {@code NotificationRecipientResolver} adds them to every
     * vulnerability-scoped email directly, ahead of the switched audiences.
     */
    REMEDIATION_OWNER("Remediation owner"),

    /**
     * Users whose access to the application comes from the organization it belongs to —
     * typically customer-side users rather than staff.
     *
     * <p>Resolved through {@code AccessScopeService.ownsApplication}, the same rule the API
     * enforces, so an app-level restricted user only hears about their own applications.
     * That matters because every one of these emails links into the platform: mailing
     * someone about a finding they would get a 403 on is worse than not mailing them.
     */
    ORG_USERS("Organization access");

    private final String label;

    EmailNotificationAudience(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
