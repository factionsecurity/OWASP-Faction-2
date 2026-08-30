package com.faction.clientportal.util;

/**
 * Escapes SQL {@code LIKE} wildcards so a user-supplied search term matches literally.
 * Feed the escaped term into a {@code LIKE ... ESCAPE '!'} predicate.
 *
 * <p>Without this, {@code %} and {@code _} in the input act as wildcards — e.g. searching
 * {@code "app_001"} would also match {@code "appX001"}. {@code '!'} is used as the escape
 * character (rather than backslash) to avoid triple escaping across Java string literal →
 * JPQL → SQL.
 */
public final class LikeEscaper {

    /** Escape character; pair with {@code ESCAPE '!'} in the query. */
    public static final char ESCAPE_CHAR = '!';

    private LikeEscaper() {
    }

    public static String escape(String term) {
        if (term == null) {
            return null;
        }
        // Escape the escape char first, then the two wildcards.
        return term.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }
}
