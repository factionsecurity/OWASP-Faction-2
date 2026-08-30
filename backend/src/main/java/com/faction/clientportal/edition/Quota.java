package com.faction.clientportal.edition;

/**
 * A countable resource the open source edition caps.
 *
 * <p>Unlike {@link Feature}, the capability itself ships in the open source build —
 * only the number is limited. The key is stable API for the same reason.
 */
public enum Quota {

    /** Configured LLM providers. */
    AI_PROVIDERS("ai_providers", "AI providers"),

    /** Saved AI prompt templates. Community seeds none, so all four slots are the operator's. */
    AI_PROMPTS("ai_prompts", "AI prompts"),

    /** Installed App Store extensions. The extension SDK itself is open source. */
    EXTENSIONS("extensions", "App Store integrations");

    private final String key;
    private final String displayName;

    Quota(String key, String displayName) {
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
