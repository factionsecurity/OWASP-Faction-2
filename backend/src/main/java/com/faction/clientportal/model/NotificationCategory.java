package com.faction.clientportal.model;

import java.util.Arrays;

/**
 * Groups notification types into things a user can sensibly switch on or off.
 *
 * <p>Preferences are expressed per category rather than per type so the settings page
 * stays readable and adding a new notification type does not silently create an option
 * nobody knows about — a new type maps to an existing category, or the category list
 * grows deliberately.
 */
public enum NotificationCategory {

    MENTION("Mentions",
            "When someone @mentions you in a comment or an assessment note.",
            "MENTION"),

    ASSESSMENT_ASSIGNED("Assessment assignments",
            "When you are assigned to an assessment, or made its engagement or remediation manager.",
            "ASSESSOR_ASSIGNED", "ASSESSMENT_CREATED"),

    RETEST_ASSIGNED("Retest assignments",
            "When a retest is assigned to you.",
            "RETEST_ASSIGNED"),

    THREAD_COMMENT("Comments on items you follow",
            "When someone comments on a finding you are assigned to.",
            "COMMENT_ADDED"),

    /** Anything not mapped above, so a new type is never silently unswitchable. */
    OTHER("Other notifications",
            "Everything else Faction notifies you about.");

    private final String label;
    private final String description;
    private final String[] types;

    NotificationCategory(String label, String description, String... types) {
        this.label = label;
        this.description = description;
        this.types = types;
    }

    public String label() { return label; }

    public String description() { return description; }

    /** Maps a notification's {@code type} string onto its category. */
    public static NotificationCategory forType(String type) {
        if (type == null) return OTHER;
        return Arrays.stream(values())
                .filter(c -> Arrays.stream(c.types).anyMatch(t -> t.equalsIgnoreCase(type)))
                .findFirst()
                .orElse(OTHER);
    }
}
