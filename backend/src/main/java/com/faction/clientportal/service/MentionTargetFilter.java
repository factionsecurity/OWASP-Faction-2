package com.faction.clientportal.service;

import com.faction.clientportal.model.MentionTargetType;

/**
 * Which section of the mentions dashboard an operation applies to.
 *
 * <p>A plain {@link MentionTargetType} cannot express this: the dashboard has a section
 * for rows with <em>no</em> target — notifications recorded before that context was
 * captured — and clearing that section is a different request from clearing the feed,
 * which is what a null enum already means.
 */
public record MentionTargetFilter(MentionTargetType targetType) {

    /** The "Other" section: mention rows carrying no target type. */
    public static final MentionTargetFilter UNTARGETED = new MentionTargetFilter(null);

    public boolean isUntargeted() {
        return targetType == null;
    }

    /**
     * Parses the {@code targetType} request parameter. Blank or absent means the whole
     * feed (null); {@code NONE} means the untargeted section; anything else must name a
     * target type.
     */
    public static MentionTargetFilter parse(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.equalsIgnoreCase("NONE")) return UNTARGETED;
        return new MentionTargetFilter(MentionTargetType.valueOf(value.toUpperCase()));
    }
}
