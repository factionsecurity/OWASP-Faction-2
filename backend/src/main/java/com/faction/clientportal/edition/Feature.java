package com.faction.clientportal.edition;

/**
 * A capability that the open source edition does not include.
 *
 * <p>Each constant is one paid feature. The key is what crosses the wire to the
 * frontend and appears in a 402 body, so it is stable API — rename the enum constant
 * freely, never the key.
 *
 * <p>Features are all-or-nothing. Numeric caps are {@link Quota} instead.
 */
public enum Feature {

    /** SAML / Entra single sign-on, and directory-backed user lookup. */
    SSO("sso", "Single Sign-On"),

    /** Password-protected PDF deliverables. Plain DOCX and PDF generation is open source. */
    ENCRYPTED_PDF("encrypted_pdf", "Encrypted PDF Reports"),

    /** White-label logos, colours and sign-in backgrounds. */
    BRANDING("branding", "Custom Branding"),

    /** Inbound mailbox polling, reply-to-comment threading and reply tracking. */
    INBOUND_EMAIL("inbound_email", "Email Inbox Monitoring"),

    /**
     * Prompt and completion audit logging, plus the token usage chart.
     *
     * <p>Token <em>accounting</em> is open source — an operator can always see what
     * their AI spend is. This flag covers the stored request/response audit trail and
     * the reporting view built on top of it.
     */
    AI_OBSERVABILITY("ai_observability", "AI Logging & Usage Analytics"),

    /** External application owner portal, stakeholders and the sub-organization directory. */
    EXTERNAL_OWNERS("external_owners", "External Owner Portal"),

    /** Creating and editing roles. Community ships Super Admin and Pentester, fixed. */
    CUSTOM_ROLES("custom_roles", "Custom Roles & RBAC"),

    /**
     * Report sections: a template defines named sections, each finding is filed under one,
     * and the DOCX renders a table and findings block per section.
     *
     * <p>Gated where sections come into being — defining them on a template — rather than
     * on every finding. A finding can only be filed under a section its assessment has, and
     * an assessment only has the sections its template had, so nothing downstream needs its
     * own check. The report generator additionally ignores section data in this edition,
     * so a database that once ran the overlay still produces a whole report.
     */
    REPORT_SECTIONS("report_sections", "Report Sections");

    private final String key;
    private final String displayName;

    Feature(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    public String getKey() {
        return key;
    }

    public String getDisplayName() {
        return displayName;
    }
}
