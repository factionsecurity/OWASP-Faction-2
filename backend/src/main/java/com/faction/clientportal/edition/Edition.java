package com.faction.clientportal.edition;

/**
 * Which build of Faction is running.
 *
 * <p>This is reported, never configured. The edition is a consequence of what is on
 * the classpath: when the enterprise overlay is absent, {@link #COMMUNITY} is the only
 * answer available. See {@link EditionPolicy} for why that matters.
 */
public enum Edition {
    COMMUNITY,
    ENTERPRISE
}
